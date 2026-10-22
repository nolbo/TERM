# Distribution-independent AVF Linux

The original implementation coupled the manifest verifier, signed catalog,
Environment validation, guest factory and initial package refresh to Debian.
Ubuntu and Arch must share the same install/management experience while retaining
independent package databases, VM identity and persistent storage.

## Contract

| Distribution | Manifest ID | Signed catalog ID | Package manager |
| --- | --- | --- | --- |
| Debian | `debian` | `debian-arm64-current` | APT/dpkg |
| Ubuntu 24.04 LTS | `ubuntu` | `ubuntu-arm64-current` | APT/dpkg |
| Arch Linux ARM | `archlinux` | `archlinux-arm64-current` | pacman |

`EnvironmentDistribution` is immutable environment metadata. All three use
`EnvironmentRuntime.AVF` and `SessionRuntime.AVF`; session IDs, leases, VM owners,
Binder operations, capabilities, exports and port mappings remain shared.
`RuntimeConfig.imageDistribution` comes from authoritative host environment
metadata. Installation and driver preparation reject a verified manifest for a
different distribution, including on startup of an existing image. A missing
catalog entry never falls back to another distribution.

`AvfImageManifest`, `AvfImageLayout`, `AvfImageVerifier`, `AvfImageInstaller` and
`AvfImageRelease` implement one five-artifact disk contract. A release includes
kernel, initrd, immutable system/config disks and a persistent writable root disk
(the compatible artifact name remains `home.img`).
The manifest hash and artifact hashes remain pinned; normal guest writes never
invalidate immutable artifacts. An installer never replaces an existing image.

### EXT4 root (image format v2)

New Debian, Ubuntu and Arch images boot `root=/dev/vdb rootfstype=ext4 rw`.
`home.img` contains the complete root filesystem, including packages, settings,
home and container data. `system.img` remains an immutable, independently verified
baseline mounted at `/.droid/lower` with `ro,noload,nodev,nosuid,noexec`.
`/.droid/state` is an ordinary directory on the writable root, retaining explicit
Docker/ReDroid storage paths. There is no OverlayFS root or upper/work tree.

The old root prevented Docker overlay upper/work directories under `/var/lib`.
Direct EXT4 also avoids root-level copy-up. Keeping a separate sealed baseline
preserves the existing hash and three-way update model; the cost is storing a
full initial root as well as its baseline. Sparse storage avoids allocating zeros
but does not deduplicate the root's nonzero data against the baseline.

Format v2 makes old APKs reject the layout instead of booting the wrong disk.
The new host continues to verify and boot installed v1 OverlayFS images using
their original initrd and `vda ro` parameters. A v1 → v2 update requires a full
transition even when kernel hashes match; components cannot change root format.
Ordinary updates do not downgrade v2 to v1. Explicit rollback restores the whole
old generation and its matching boot format. No installed environment is modified
by an APK update alone. Approved v1 catalog pins stay valid until separately
produced v2 images pass the release gates and are published/promoted.

### Installation storage

The raw EXT4 contract uses a system disk of at least 6 GiB. A v2 root disk adds
8 GiB to the system disk's logical length (normally 14 GiB total), retaining room
for the complete base plus the former writable-state budget. Legacy v1 state
disks remain 8 GiB. Logical capacities do not require allocating every host block.
The shared installer writes nonzero blocks, retains zero regions as sparse holes
and explicitly sets the exact final file length, including trailing zeros.
Online gzip extraction and offline imports use the same bounded/cancellable
writer; logical bytes and published hashes remain unchanged. Existing approved
images therefore work without new image publication or catalog changes.

An installation attempt owns a sibling `.install-` directory. After full artifact
verification, the installer records its manifest/initialization receipts and
renames that directory under the existing activation fence. The compatibility
copying API also preserves sparsity and retains its caller's source directory.
Worker staging includes a stable work ID; retry cleanup excludes active work,
offline imports, other environments and installed disks. Existing environments
are neither replaced nor compacted. AVF environment usage counts allocated
512-byte filesystem blocks. Guest writes grow
physical usage, and guest free space does not reserve Android storage in advance.
Allocation savings depend on the target filesystem and package initialization;
producer measurements are not device measurements.

## Factory and guest management

`build-avf-image.py --distribution` selects the target rootfs. Debian retains the
Google AVF base. Ubuntu and Arch use the same checksum-pinned base archives as
Userspace Linux (`avf-distributions.json`), prepared in an isolated ARM64 build
container with systemd, DHCP/resolver, sudo and native package tools. Target
rootfs preparation verifies the archive before extraction or executing guest code.
Ubuntu explicitly installs `udev`; every prepared guest must provide `systemd-udevd`.
Without it, systemd cannot activate the config disk's device unit, waits for
`/dev/vdc`, and enters emergency mode before sessiond starts. A devtmpfs node or
the old PID-1 self-test does not establish a normal systemd boot.
During image production, a rootfs bootstrap account at UID/GID 1000 is renamed to
`droid` and its home is moved to `/home/droid`, preserving files and numeric
ownership. The factory locks its password and validates passwordless sudo.
This normalization never runs against an installed environment.

All new Debian, Ubuntu and Arch images require the same container-capable AVF
kernel recipe and its matching modules under [AVF_CONTAINER_KERNEL.md](AVF_CONTAINER_KERNEL.md).
The factory replaces both Google boot inputs before generating the common
initramfs EXT4 boot layer. `configure-avf-boot.sh` creates that layer independently of
`configure-avf-guest.sh`, which installs the common session/capability/port/file
services and validates the selected userspace. No Debian package database is
transplanted into Ubuntu or Arch. Runtime disk assembly and guest configuration
remain inside libguestfs, with no host mount or privileged container.
Each rootfs explicitly installs iproute2 and iptables, including the legacy and
nft frontends, for the two-boot namespace/IPC/cgroup/BinderFS/DMA-heap/NAT checks.
Docker, LXC and ReDroid remain optional guest packages. ReDroid uses the official
ARM64 64only image when the AVF CPU does not provide AArch32 execution.

New images declare `packageInitializationVersion=1`. The fixed host-CID-only
maintenance operation on 40503 runs `initialize-avf-packages.sh`: strict full APT
index refresh on Debian/Ubuntu, pacman repository database refresh on Arch.
The refresh does not install or upgrade user packages. Its lock, timeout,
process-group cancellation, flush-before-receipt and idempotent completion rules
are common. No caller-selected command, path or repository is accepted. Arch
users still use full `pacman -Syu` for normal rolling-release package upgrades.

Offline factory gates boot twice and verify the RO baseline, writable-EXT4-root
persistence, services/network/shares, non-root `droid` plus sudo, real local package
installation/removal and initialization failure/success/idempotence. A candidate
cannot be approved based only on host tests or a successful archive download.
Two additional normal systemd boots use a disposable state-disk copy and a real
vhost-vsock device. A host-CID session-list RPC on 40500 and a connection to the
package initializer on 40503 must both succeed on each boot. The producer records
these results in `provenance.json`; the distributed root disk stays clean.
Host-side probing preserves Docker's default seccomp policy, which blocks
container AF_VSOCK sockets. Producers need the host `vhost_vsock` module enabled.

## Compatibility and publication

Legacy Debian manifests and `aptInitializationVersion=1` remain accepted. The
host reads old `.apt-index-initialization` receipts without resetting them.
Persisted worker class/work names and phase `APT_REFRESH`, opcode `0x40`, and the
APK catalog resource path remain compatible. These names are compatibility
identifiers, not distribution selection. Existing VMs and disks are not migrated.

A single-release catalog remains valid. A multi-release catalog uses
`formatVersion=2`, `distributions=debian,ubuntu,archlinux` and distribution-prefixed
release properties. Approval and candidate APK creation merge entries while
preserving other reviewed pins. Each added pin must pass producer smoke boots,
public-download hash checks and exact APK-catalog verification under
[IMAGE_RELEASE.md](IMAGE_RELEASE.md).

The committed default catalog includes separately published Debian, Ubuntu and
Arch pins. A corrected image requires a new immutable release ID, public download
verification and catalog promotion. Existing installed images and writable disks
are not automatically replaced; an environment using a faulty base image must use
an explicit image transition or a new environment while preserving its old data.
Factory verification requires at least 24 GiB free space, the two fixture boots
and two normal systemd/vsock boots; device verification requires a privileged AVF-capable ARM64 target.
Host unit tests alone do not establish these production/runtime criteria.

## Decision and alternatives

Explicit same-distribution component/image updates are specified in
[AVF_UPDATES.md](AVF_UPDATES.md). They require a catalog declaring
`maintenanceVersion=1` and retain complete previous generations.

Use one AVF backend and a shared boot/guest contract with distribution adapters.
Separate AVF runtime variants would duplicate lifecycle/storage dispatch and
persisted identities. Per-distribution kernels/initramfs systems would add
unverified AVF device/configuration compatibility. Sharing a paired AVF boot layer
keeps those boundaries stable, at the cost of requiring producer smoke tests to
prove each target userspace works with that kernel. Rootless and AVF keep separate
rootfs/package/home state even when they start from the same archive.
