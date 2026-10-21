# AVF Ubuntu container kernel

## Baseline and validation status

The approved `avf-ubuntu-20261020-r4` Image is Google's
`6.12.60-android16-6-gf33ca06267f8-ab14749849-4k`, SHA-256
`121fcef9c308fa5bbf2722417880bc805d6c0858608634fe4ee834f567e4ac07`.
Its embedded IKCONFIG disables PID namespaces, System V IPC, POSIX queues,
PIDs/device cgroups, xtables addrtype and the system DMA-BUF heap. IPC namespaces
are unavailable because their IPC prerequisites are disabled. Installing Docker
packages cannot repair these kernel limitations.

This implementation is a build/packaging path, **not a published or
device-verified kernel**. On the available ARM64 host, the exact official common
source plus the fragment passes `make olddefconfig` and all 100 configuration
assertions. Host Clang 18 was used solely for Kconfig dependency checks; Rust
availability differs from the official toolchain. This is not the release
`.config`, and no Image or matching modules have been compiled. Official Kleaf's
Linux x86_64 prebuilts cannot execute on this host. The connected ReDroid Android
15 target has no AVF feature/APEX. No new AVF Ubuntu boot, container feature
runtime probe, Docker `hello-world`, LXC container or nested ReDroid launch has
passed. The approved catalog and existing installations retain the old kernel.

## Build and packaging

`image/kernel/source.json` pins the original Image and Android common source
commit. `build-avf-kernel.py` extracts the **existing full configuration** from
the hash-verified Image and adds `containers.fragment` through official Kleaf
rules. All matching modules and their initramfs are rebuilt together.

The fragment enables PID/IPC/user namespaces, System V IPC, POSIX queues,
PIDs/device cgroups and existing CPU/memory/BPF prerequisites. User namespaces
support LXC ID isolation inside the guest; Ubuntu's userspace policy still
applies. Bridge/veth, conntrack, IPv4/IPv6 NAT, addrtype and nftables/xtables
compatibility cover iptables-nft and iptables-legacy. The system DMA-BUF heap
is added while retaining BinderFS, memfd, seccomp, IPv6, OverlayFS, 4 KiB pages,
virtio/vsock/FUSE, SELinux, CFI and module signatures.

New features are built in to avoid module-autoload/version problems. No
Docker/LXC/ReDroid packages, ashmem, CMA heap or Swarm/IPVS/VXLAN drivers are
added. Ubuntu adds iproute2/iptables for the probe. Kernel assets remain image
downloads, not APK payloads. The Android host kernel, AVF authorization,
protection mode and boot parameters are unchanged.

Use a Linux x86_64 machine with `repo` and official Android build prerequisites,
with fresh checkout/output directories:

```sh
mkdir -p image/build/kernel-checkout
cd image/build/kernel-checkout
avf_manifest=$(python3 -c 'import json; print(json.load(open("../../kernel/source.json"))["manifestRevision"])')
avf_common=$(python3 -c 'import json; print(json.load(open("../../kernel/source.json"))["commonRevision"])')
repo init --depth=1 -u https://android.googlesource.com/kernel/manifest -b "$avf_manifest"
repo sync -c --no-tags --no-clone-bundle --fail-fast -j4
git -C common fetch --depth=1 origin "$avf_common"
git -C common switch --detach "$avf_common"
cd ../../..
python3 image/scripts/build-avf-kernel.py --checkout image/build/kernel-checkout \
  --output image/build/kernel-bundle
```

The manifest pin defines the official layout but contains some branch revisions.
The builder saves the complete resolved `repo manifest -r` and its hash. Preserve
`source-manifest.xml` to reproduce the exact toolchain/build checkout; the
manifest pin alone does not freeze every external project.

The builder rejects a modified/wrong common source or existing output/package.
It installs a temporary `//term_avf` package, calls
`tools/bazel run //term_avf:dist`, then removes only that package. Output contains
`vmlinuz`, `initrd.img` (matching module ramdisk), `kernel.config`,
`source-manifest.xml` and `kernel-build.json`. The Image's embedded config must
equal the final Kleaf `.config`; all required features, boot prerequisites and
retained hardening flags must pass validation.

```sh
python3 image/scripts/build-avf-image.py --distribution ubuntu \
  --kernel-bundle image/build/kernel-bundle \
  --release-id <new-immutable-id> \
  --base-uri https://github.com/nolbo/TERM/releases/download/<new-immutable-id>/ \
  --output image/build/<new-immutable-id>
```

Ubuntu production rejects a missing/invalid bundle before Docker/downloads. The
factory replaces **both** Google kernel/module inputs. Inside the existing
isolated appliance, the module-tree release must match the new kernel metadata,
then depmod and the existing overlay initramfs generation run. Final config and
build identity are installed in the immutable system's `/usr/share/term/`.
The existing five-artifact manifest hashes the actual new kernel and generated
boot ramdisk. No host mounts or privileged build container are introduced.

The Ubuntu workflow builds the kernel on x86_64 before ARM64 image production
and refuses to reuse old candidates without new kernel/probe provenance.
Debian/Arch production retains its existing kernel path. Publication, public
download verification, catalog promotion and signing follow
[IMAGE_RELEASE.md](IMAGE_RELEASE.md); this change publishes nothing. Different
kernel hashes require an explicit **full image transition**, not a component
update. Preserve existing system/state for rollback under
[AVF_UPDATES.md](AVF_UPDATES.md). Never edit installed hashes or replace kernels
through guest-accessible Android storage.

## Runtime acceptance

Both offline QEMU boots must return `TERM_AVF_CONTAINERS_OK`. The probe compares
running `/proc/config.gz`, `uname -r` and page size against packaged build
metadata, checks memfd and `/dev/dma_heap/system`, then uses disposable PID/IPC/
mount/network namespaces to test PID 1, SysV shared memory, POSIX queue creation
and mqueue mount, cgroup2 PIDs/memory controller availability, BinderFS control,
bridge/veth creation and IPv4/IPv6 addrtype/conntrack/NAT rule insertion with
**both** iptables backends. This does not prove packet forwarding, cgroup limit
enforcement or a complete Android container boot. It runs only in factory tests
or by explicit guest-root invocation:

```sh
sudo /usr/local/lib/droid/image-container-selftest
```

Normal-systemd two-boot session RPC/vsock and private-share gates remain
mandatory. Build and offline probe results enter release provenance.
`verify-avf-release.py --require-container-kernel` rejects incomplete/old
candidates; existing published releases remain readable.

On an authorized AVF target, install a separately verified candidate through the
normal catalog/image path and run the probe in **that guest**. Check normal
Ubuntu sessions, sudo, persistence, networking and shares across reboot.
Where guest package/Internet access is available, install container tools, run
`sudo docker run --rm hello-world`, test an LXC container and launch the intended
ARM64 ReDroid version with its documented BinderFS/memfd/graphics settings.
Container privileges remain guest-local and grant no Android capability.
Report every runtime result separately.

## Primary references

- [Official Android kernel builds](https://source.android.com/docs/setup/build/building-kernels)
- [Exact common source](https://android.googlesource.com/kernel/common/+/f33ca06267f86f1974bd273c0cce78f614d6949e/)
- [Official Linux server target](https://android.googlesource.com/kernel/common-modules/virtual-device/+/refs/heads/android16-6.12/BUILD.bazel)
- [Kleaf config ordering](https://android.googlesource.com/kernel/build/+/refs/heads/main-kernel-2025/kleaf/docs/kernel_config.md)
- [Docker feature checker](https://github.com/moby/moby/blob/master/contrib/check-config.sh)
- [ReDroid heap/BinderFS requirements](https://github.com/remote-android/redroid-doc/blob/master/deploy/openeuler.md)
