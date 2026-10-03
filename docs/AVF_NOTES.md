# AVF notes

## Deployment constraint

The custom Linux VM path needs `MANAGE_VIRTUAL_MACHINE` and
`USE_CUSTOM_VIRTUAL_MACHINE`. These are privileged/signature permissions on the
relevant Android releases. Declaring them in a normal APK does not grant them.

## Packaged driver and platform boundary

Ordinary APK builds include `AospAvfPlatformDriver` through `:aosp-avf-runtime`. `TerminalApplication` supplies the runtime provider while retaining Native as the default owner. Selecting AVF creates a separate backend only after checking Android 15+, the AVF feature, and both actual permission grants. The optional `android.system.virtualmachine` shared library is supplied by the device; compile-only API declarations are never APK runtime classes.

Custom-image configuration methods are hidden in AOSP and absent even from ordinary System SDK stubs. A compatible platform implementation and authorization to call those methods remain necessary in addition to the two permissions. Packaging the driver does not grant permissions or bypass API policy. Unsupported devices receive an explicit AVF error and continue using Native/Rootless. See `platform/aosp-avf-runtime/README.md` for ABI pinning, an exact OEM framework-jar override, and APK/device validation.

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
architecture, distribution, non-empty files, and hashes before installation. The installer records the verified manifest hash in app-private storage. Subsequent starts still verify the immutable kernel, initrd, system and config files; the writable home/state disk is checked for presence and non-empty size, because legitimate guest writes change its hash. A directory without an installation receipt must pass all five artifact checks before adoption. ISO import is not supported.

The installer accepts only a complete staging directory, copies it into a
sibling temporary directory, verifies it again, and then promotes it to the VM
location. It never replaces an existing image; upgrade/rollback policy remains
a separate future design decision.

Image download is a one-time WorkManager task, not a VM owner. It accepts only
a release ID from a bundled/OEM catalog; the catalog pins an HTTPS manifest URL,
its SHA-256 hash, and an artifact-size limit. The image factory produces the catalog. Ordinary builds embed the reviewed `image/catalog-approved.properties` when present, and `-PavfImageCatalog=...` overrides it for an isolated product/candidate build. Without either input the source build retains an empty catalog. Default approval requires successful unauthenticated public downloads and remains a reviewed source change. HTTPS asset redirects are bounded and cannot downgrade to HTTP; both compressed transfers and decompressed artifacts have size limits. The pinned manifest and uncompressed artifact hashes remain mandatory.

The runtime process requests work through WorkManager's multiprocess API, which runs the installer in the configured main application process. Installation uses a data-sync foreground notification. The pending session waits for completion and continues automatically; failure/cancellation reports an error. Existing images are never overwritten, including invalid installations. Both the public and AOSP application classes provide the same main-process WorkManager configuration.

## Host vsock binding

The AOSP `VirtualMachine` system API exposes `connectVsock(port)` as a
`ParcelFileDescriptor`. The runtime wraps that descriptor in
`ParcelFileDescriptorFrameTransport`, which validates complete protocol frames
and owns descriptor closure. The UI never receives a file descriptor or direct
vsock access.

## Privileged AOSP deployment driver

`platform/aosp-avf-runtime` contains `AospAvfPlatformDriver`, compiled and packaged by Gradle against compile-only platform ABI declarations (or an OEM-supplied full framework jar). It creates/reuses a named VM with a
custom image configuration, waits for the AVF ready callback after `run()`, and
then retries port 40500 until `droid-sessiond` accepts a vsock connection.
Later AVF errors and stop callbacks are fed back into `AvfBackend`, so the UI
does not claim that a dead VM still owns persistent sessions.

The first disk mapping is fixed and part of the signed image ABI:

```text
vda=system.img (RO), vdb=home.img (RW), vdc=config.img (RO)
```

The automated producer uses Google's direct-kernel ARM64 Debian image as the base. It preserves the original kernel and restores its matching modules from the paired module-only initrd, since the rootfs has no installed kernel package. It installs the three TERM services and CLI, generates a boot initrd, and mounts the read-only vda filesystem as an overlay lower layer with persistent upper/work directories on vdb. Thus home files and root filesystem/package changes survive reboot without modifying the distributed system image. The AOSP driver explicitly passes `root=/dev/vda rootfstype=ext4 ro console=hvc0`. The config disk is mounted read-only at `/etc/droid-config`. See `IMAGE_RELEASE.md` for the producer and its two-boot QEMU gate.

AVF `getOrCreate` retains an existing VM configuration. Image upgrade/reset is
therefore deliberately deferred to an explicit migration design; the runtime
does not replace an image under an existing VM instance.

Legacy storage adoption assigns a registry UUID without moving the image or replacing
`droid-default`. It waits until the service can reach that VM's PTY owner and prove no
active sessions. The persisted runtime-to-UUID association resolves the adopted UUID
back to `vm/default`, `shared-vms/default` and `droid-default`; the AOSP deletion driver
verifies the original instance is stopped before deleting it. A disconnected/unavailable
VM remains protected. This path still requires validation on the matching privileged
AOSP product; APK compilation and unsupported-device tests do not establish a successful VirtualMachineManager boot.

## Next validation sequence

1. Build the packaged driver, optionally using the target AOSP full framework jar, and verify API compatibility.
2. Provision one known-good ARM64 Debian image and immutable config image.
3. Create/start the VM from `VmRuntimeService`.
4. Establish a vsock connection to `droid-sessiond`.
5. Prove attach/detach/reconnect against one PTY before adding capabilities,
   GUI, ISO import, or multi-distro support.
