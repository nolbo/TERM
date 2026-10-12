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
kernel, initrd, immutable system/config disks and a persistent writable home disk.
The manifest hash and artifact hashes remain pinned; normal guest writes never
invalidate immutable artifacts. An installer never replaces an existing image.

## Factory and guest management

`build-avf-image.py --distribution` selects the target rootfs. Debian retains the
Google AVF base. Ubuntu and Arch use the same checksum-pinned base archives as
Userspace Linux (`avf-distributions.json`), prepared in an isolated ARM64 build
container with systemd, DHCP/resolver, sudo and native package tools. Target
rootfs preparation verifies the archive before extraction or executing guest code.

The paired Google AVF kernel and modules supply the common initramfs/overlay
boot layer. `configure-avf-boot.sh` creates that layer independently of
`configure-avf-guest.sh`, which installs the common session/capability/port/file
services and validates the selected userspace. No Debian package database is
transplanted into Ubuntu or Arch. Runtime disk assembly and guest configuration
remain inside libguestfs, with no host mount or privileged container.

New images declare `packageInitializationVersion=1`. The fixed host-CID-only
maintenance operation on 40503 runs `initialize-avf-packages.sh`: strict full APT
index refresh on Debian/Ubuntu, pacman repository database refresh on Arch.
The refresh does not install or upgrade user packages. Its lock, timeout,
process-group cancellation, flush-before-receipt and idempotent completion rules
are common. No caller-selected command, path or repository is accepted. Arch
users still use full `pacman -Syu` for normal rolling-release package upgrades.

Offline factory gates boot twice and verify the RO lower layer, writable-overlay
persistence, services/network/shares, non-root `droid` plus sudo, real local package
installation/removal and initialization failure/success/idempotence. A candidate
cannot be approved based only on host tests or a successful archive download.

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

The current committed default catalog remains Debian. Ubuntu/Arch source support
requires new factory candidates, public verification and pin promotion before
online installation is available in ordinary APKs. This implementation does not
publish releases. Factory verification requires at least 24 GiB free space and two successful
offline boots; device verification requires a privileged AVF-capable ARM64 target.
Host unit tests alone do not establish these production/runtime criteria.

## Decision and alternatives

Use one AVF backend and a shared boot/guest contract with distribution adapters.
Separate AVF runtime variants would duplicate lifecycle/storage dispatch and
persisted identities. Per-distribution kernels/initramfs systems would add
unverified AVF device/configuration compatibility. Sharing a paired AVF boot layer
keeps those boundaries stable, at the cost of requiring producer smoke tests to
prove each target userspace works with that kernel. Rootless and AVF keep separate
rootfs/package/home state even when they start from the same archive.
