# AVF notes

## Deployment constraint

The custom Linux VM path needs `MANAGE_VIRTUAL_MACHINE` and
`USE_CUSTOM_VIRTUAL_MACHINE`. These are privileged/signature permissions on the
relevant Android releases. Declaring them in a normal APK does not grant them.

## Packaged driver and platform boundary

Ordinary APK builds include `AospAvfPlatformDriver` through `:aosp-avf-runtime`. `TerminalApplication` supplies the runtime provider while retaining Native as the default owner. Selecting AVF creates a separate backend only after checking Android 15+, the AVF feature, and both actual permission grants. The optional `android.system.virtualmachine` shared library is supplied by the device; compile-only API declarations are never APK runtime classes.

Custom-image configuration methods are hidden in AOSP and absent even from ordinary System SDK stubs. A compatible platform implementation and authorization to call those methods remain necessary in addition to the two permissions. Packaging the driver does not grant permissions or bypass API policy. Unsupported devices receive an explicit AVF error and continue using Native/Rootless. See `platform/aosp-avf-runtime/README.md` for ABI pinning, an exact OEM framework-jar override, and APK/device validation.

After the device/permission checks, backend creation allocates a custom-image builder before requesting installation or creating a VM. A linkage error here rejects startup immediately and explains both an incompatible ABI and non-SDK policy denial, retaining the original error as its cause. AOSP's pinned `VirtualMachineCustomImageConfig.Builder` has a no-argument constructor; `No direct method <init>()V` alone does not prove a different constructor, since ART also reports blocked hidden methods as missing methods. ADB grants for the two VM permissions do not by themselves authorize these hidden APIs. Use the target framework's actual signatures and the app's hidden-API logcat entries to distinguish the two cases; see `DEPLOYMENT.md`.

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

New producer manifests declare optional `aptInitializationVersion=1`, which identifies the fixed host-only guest APT initializer. Online installs of those verified images refresh the full indexes through `:vmhost` before reporting completion and preserve the disks for retry on failure. Older manifests omit this field and retain file-only installation; unknown initialization versions are rejected. Existing installed images are not migrated.

Image download is a one-time WorkManager task, not a VM owner. It accepts only
a release ID from a bundled/OEM catalog; the catalog pins an HTTPS manifest URL,
its SHA-256 hash, and an artifact-size limit. The image factory produces the catalog. Ordinary builds embed the reviewed `image/catalog-approved.properties` when present, and `-PavfImageCatalog=...` overrides it for an isolated product/candidate build. Without either input the source build retains an empty catalog. Default approval requires successful unauthenticated public downloads and remains a reviewed source change. HTTPS asset redirects are bounded and cannot downgrade to HTTP; both compressed transfers and decompressed artifacts have size limits. The pinned manifest and uncompressed artifact hashes remain mandatory.

After saving an AVF profile with new storage, the editor requests installation only if the user confirms it. The same explicit action is available later in saved profile settings. WorkManager's multiprocess API runs the installer in the configured main application process with a data-sync foreground notification. Installation does not boot a VM or allocate a TAP interface. A session can wait for an already approved installation and continue after completion; session startup never requests a new download. Every profile sharing the storage observes its persisted progress inline. Installation status reflects image files rather than setup preferences or VM readiness. Existing images are never overwritten, including invalid installations. Both the public and AOSP application classes provide the same main-process WorkManager configuration.

## Guest DHCP and DNS

Factory images install `/etc/systemd/network/10-term-virtio.network`, matching `Driver=virtio_net` instead of a PCI-dependent interface name. `systemd-networkd` obtains addresses, routes and DNS through DHCP; DHCP hostname changes are disabled to preserve the driver's `localhost` hostname. The producer requires and enables both `systemd-networkd` and `systemd-resolved`. `/etc/resolv.conf` points to `/run/systemd/resolve/stub-resolv.conf`, so libc and programs reading that file use the same guest resolver and DHCP-provided DNS. No build-container resolver or fixed public DNS address is shipped.

The previously approved `avf-debian-20261004` image retained a regular Docker-generated `/etc/resolv.conf` pointing to `169.254.169.254`, and did not install a TERM network rule for the virtio NIC. A reported `enp0s8` stayed down with no address or default route despite both services being active. Thus replacing DNS alone does not repair the missing DHCP configuration. The producer seals the resolver symlink after `virt-customize` exits and checks the configuration on both offline QEMU boots. Those boots still use `-nic none`; their gate checks installed policy, service enablement and the final symlink, not live DHCP, DNS or Android tethering.

This change applies to newly produced images; an APK update does not rewrite an installed image or its persistent overlay. Existing VM repair commands are documented in [the Korean user guide](USER_GUIDE.ko.md#avf에서-apt가-dns-오류로-실패할-때). If DHCP cannot supply an address or route after that repair, inspect Android TAP/tethering on the actual device. Vsock terminal access does not establish Internet connectivity.

## Fixed TAP networking on some products

At the pinned AOSP ABI revision, [VirtualizationService ignores the requested TAP suffix and uses `fixed`](https://android.googlesource.com/platform/packages/modules/Virtualization/+/175a51b30123fa6b02b541f1969665708f7ec2c3/android/virtualizationservice/src/aidl.rs#526); its TODO requires a bridge before using per-VM names. The custom-image Java API exposes only the network enable boolean, without an app-controlled TAP name. A denial involving `avf_tap_fixed`, `TUNSETIFF` and `EACCES` when a second independently stored VM starts is consistent with this platform restriction, rather than a profile/storage identity collision. The driver adds an explanation and keeps the original failure as its cause. Other permissions, image and VM errors remain unchanged.

Concurrent internet-connected VMs on such a product require platform TAP/bridge and tethering support. Existing VMs and requested network settings remain unchanged when creation fails. The current driver cannot supply distinct TAP names through the custom-image API; an APK-side VM rename or additional VM permission grant does not change the fixed-suffix service implementation. Vsock session access remains separate from the TAP device, but an offline workaround does not satisfy the requirement that both VMs have internet. Profiles sharing one storage use the same VM and do not request another TAP just to create another PTY. Successful multi-VM networking still requires validation on the target product; an unsupported emulator cannot establish it.

## Live shared directories

New producer manifests declare `virtiofsVersion=1`. The image supplies `term-shares.service`, a matching virtiofs kernel/module and a validated tag/name-table mounter. The driver adds independent app-domain `SharedPath` exports for connected shared-folder UUID directories plus a boot-local index, using the pinned AOSP ten-argument constructor and `addSharedPath`. The index's guest mount is read-only; user folders appear at `/mnt/term-shares/<name>` with `nodev,nosuid`. UUID tags stay below the 36-byte wire limit, so 64-character folder names remain supported. Folder boot configuration never exports legacy VM host paths or parent storage directories. Internal shared storage uses a separate fixed export and capability.

Live sharing requires an authorized compatible custom-image API, app-domain platform crosvm execution and a non-protected VM. Unsupported APIs/SELinux policies remain errors; an AVF feature flag or the two VM permission grants alone do not establish working virtiofs. Old manifests omit the feature and retain file copies; unknown versions are rejected. Sharing snapshots change only after VM stop/start. Host-only leases survive service death and connection edits and require platform-proven VM idleness before folder data deletion. The source change does not rebuild, replace or publish existing images. Actual mount visibility, bidirectional writes, multi-VM isolation and server teardown need validation on a matching privileged target.

New images also declare `androidStorageVersion=1`. With the existing common all-files grant, non-protected VM boots export Android's primary shared-storage root through app-domain `term-storage` and mount it at `/sdcard` with `nodev,nosuid,noexec`. No per-Environment setting is added. Folder-only and old-image boots keep their original behavior. AppOps revocation stops only recorded storage-sharing VMs, including persistent instances after owner restart; boot and revocation share a process-wide gate and stop failures retain records. Actual FUSE permissions, blocked Android paths, open-file revocation, multi-VM cleanup and SELinux behavior still need validation on the matching target.

## Host vsock binding

The AOSP `VirtualMachine` system API exposes `connectVsock(port)` as a
`ParcelFileDescriptor`. The runtime wraps that descriptor in
`ParcelFileDescriptorFrameTransport`, which validates complete protocol frames
and owns descriptor closure. The UI never receives a file descriptor or direct
vsock access.

## Privileged AOSP deployment driver

`platform/aosp-avf-runtime` contains `AospAvfPlatformDriver`, compiled and packaged by Gradle against compile-only platform ABI declarations (or an OEM-supplied full framework jar). It creates/reuses a named VM with a
custom image configuration and retries port 40500 after `run()` until `droid-sessiond` accepts a vsock connection, with a 120-second boot limit. Custom Debian guests do not send Microdroid's `onPayloadReady` notification, so it is not a startup prerequisite. The accepted connection is handed to the session client rather than closed and opened again. Platform errors and stop callbacks interrupt this wait and retain their original reason; an unreachable daemon reports its port and timeout. A failed new boot is stopped, while a previously running instance is reconnected without restarting it or replacing disks.
Later AVF errors and stop callbacks are fed back into `AvfBackend`, so the UI
does not claim that a dead VM still owns persistent sessions.

Selecting an AVF profile, including legacy/default storage, sends an explicit startup request even when Native is the primary runtime. The common selection action starts AVF rather than redirecting its unstarted VM to settings. Runtime availability reports actual primary or independent AVF owner state and never labels AVF unsupported solely because Native is the primary owner. The runtime service checks device and permission boundaries before installation/boot; the primary owner's ready state does not gate this independent AVF request. The Activity reports that preparation has started and keeps any session-creation error in a dismissible dialog. Image installation completion does not imply VM or session readiness.

For an installed image, setup and catalog guards check path existence, and the driver's `prepare` step performs the full startup checksum verification once through `DebianImageInstaller.verifyForStartup`. This also adopts fully verified older images before their writable disk changes. Corrupt images still fail preparation and are never replaced automatically. SHA-256 streaming uses a 256 KiB buffer. Additional sessions reuse the prepared runtime. Logcat tag `TermAvfDriver` records `image.verify duration_ms` and `vm.sessiond duration_ms` (including whether a running VM was reused); these separate checksum cost from VM boot/connection cost on the actual device.

The first disk mapping is fixed and part of the signed image ABI:

```text
vda=system.img (RO), vdb=home.img (RW), vdc=config.img (RO)
```

The automated producer uses Google's direct-kernel ARM64 Debian image as the base. It preserves the original kernel and restores its matching modules from the paired module-only initrd, since the rootfs has no installed kernel package. It installs the three TERM services and CLI, generates a boot initrd, and mounts the read-only vda filesystem as an overlay lower layer with persistent upper/work directories on vdb. Thus home files and root filesystem/package changes survive reboot without modifying the distributed system image. The AOSP driver explicitly passes `root=/dev/vda rootfstype=ext4 ro console=hvc0 systemd.hostname=localhost`. The config disk is mounted read-only at `/etc/droid-config`. See `IMAGE_RELEASE.md` for the producer and its two-boot QEMU gate.

The session daemon runs as `droid`, whose home and default AVF session directory are `/home/droid`. The producer creates and verifies this account home instead of a separate `/home/user` directory. Existing factory images already contain the `droid` home and need no replacement. On first registry access after the app upgrade, saved AVF profiles with the old `/home/user` default are migrated once; other custom directories and later explicit `/home/user` choices are preserved. This affects new sessions only and neither moves existing `/home/user` files nor changes live launch snapshots.

Newly produced images also give this single-user guest account passwordless sudo while locking password login. `/etc/sudoers.d/90-term-droid` is root-owned mode 0440 and checked by `visudo`; the producer requires the base image's sudo, APT and dpkg tools rather than silently producing an incomplete guest. `droid-sessiond` explicitly uses `NoNewPrivileges=no` to allow setuid sudo in its shells. The offline image gate runs as `droid`, proves sudo reaches UID 0, updates APT's normal list/cache directories from a temporary local repository, installs and removes a probe package, and checks this on both overlay boots. It does not change the guest's configured upstream sources or certify Android TAP networking, DNS, or live upstream downloads. This policy is baked into newly generated images only; APK updates do not retrofit existing system images or change the approved catalog.

AVF `getOrCreate` retains an existing VM configuration. The driver calls `setConfig` only on stopped instances before starting them, so existing installations also receive the short `localhost` guest hostname on their next boot. AOSP checks configuration compatibility; incompatible identity changes fail without deleting or recreating the VM. Running guests are reconnected without changing their configuration. Persistent VM names, including `droid-default` and independent storage IDs, remain unchanged. Image upgrade/reset is deliberately deferred to an explicit migration design; the runtime does not replace an image under an existing VM instance.

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
