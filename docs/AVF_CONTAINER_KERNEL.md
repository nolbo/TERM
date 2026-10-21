# AVF Ubuntu container kernel

## Baseline and validation status

The approved `avf-ubuntu-20261020-r4` Image is Google's
`6.12.60-android16-6-gf33ca06267f8-ab14749849-4k`, SHA-256
`121fcef9c308fa5bbf2722417880bc805d6c0858608634fe4ee834f567e4ac07`.
Its embedded IKCONFIG disables PID namespaces, System V IPC, POSIX queues,
PIDs/device cgroups, xtables addrtype and the system DMA-BUF heap. IPC namespaces
are unavailable because their IPC prerequisites are disabled. Installing Docker
packages cannot repair these kernel limitations.

Official x86_64 Kleaf compilation and bundle verification passed in
[run 39561229552](https://github.com/nolbo/TERM/actions/runs/39561229552) from
TERM commit `8de06c098580f6724dd9f5d07b5a28b8cab7e92d`. The downloaded artifact ZIP
and all bundle hashes were checked locally. The actual Image's embedded config
equals the final Kleaf config and passes all 101 requirements. Its recorded
common source revision is `f33ca06267f86f1974bd273c0cce78f614d6949e`.

| Built artifact | Verified value |
| --- | --- |
| Kernel release | `6.12.60-android16-6-maybe-dirty-term-containers` |
| Raw Image SHA-256 | `f367657afa4b9ef56217ef16a95cbe51d4c3a07301c42cc5075ff5360b390ff8` |
| Final config SHA-256 | `06d4c21e1beab6461816d6f19b1d2aab18743a517dc908755d183ad927128c21` |
| Module ramdisk SHA-256 | `4d0ddeccfe9e450ecf8760860debe091e8fb7e544a27065682bee85fde16bf45` |
| Image size | 42,981,888 bytes; 606,208 bytes (592 KiB) larger than the approved baseline |
| Artifact | `avf-ubuntu-kernel-8de06c098580f6724dd9f5d07b5a28b8cab7e92d`, ID `12328869941` |

The recipe follows the official Linux distro target by disabling the optional
Rust Binder module and retaining built-in C Binder/BinderFS; the Rust module
failed modpost with unexported IPC namespace references in an earlier attempt.
No IPC namespace symbol exports or kernel source patches were introduced.

The same run assembled `avf-ubuntu-containers-20261021-r1` and passed both
QEMU offline container probes using the new Image and generated boot ramdisk.
Both normal systemd/vsock session and package-initialization boots passed;
the second boot also passed private-share read/write and owner-loss checks.
The downloaded image ZIP and the complete five-artifact bundle passed local
release verification with container-kernel and private-share gates required.
The candidate APK build and embedded candidate-catalog verification passed.
Publishing and notification jobs were skipped; the approved catalog is unchanged.

These are QEMU results, **not physical Android AVF results**. The connected
ReDroid Android 15 target exposes neither the AVF feature nor its VM tool.
A real AVF guest boot, an LXC container and device-level networking remain
unverified. An additional QEMU run passed Docker `hello-world` with ext4-backed
`overlay2`; nested ReDroid reached Android framework initialization but failed
the full-boot check with repeated watchdog resets. Details are recorded below;
kernel/config verification alone does not establish container compatibility.

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

The optional Rust Binder module is excluded because its IPC namespace references
fail modpost in the pinned 6.12 source. This matches Google's Linux distro
fragment; the built-in C Binder driver and BinderFS remain enabled. No IPC symbol
exports or other kernel source patches are added for this workaround.

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
git -C common fetch --depth=1 https://android.googlesource.com/kernel/common "$avf_common"
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

## Additional offline container run

A separate disposable QEMU guest used the verified candidate Image, boot ramdisk
and read-only system disk with a new ext4 state disk. No existing environment
was overwritten. The running config hash and kernel release matched packaged
metadata before starting the daemon. Docker 29.1.3 ARM64 static binaries used
classic image storage (`features.containerd-snapshotter=false`), `overlay2` and
the cgroupfs driver on cgroup v2. The daemon reported its built-in seccomp
profile. A preloaded ARM64 `hello-world` image printed `Hello from Docker!` with
`--pids-limit=32 --memory=64m` on Docker's default bridge. The VM had no network
uplink, so this does not verify Internet access or end-to-end NAT forwarding.

Storage placement matters independently of kernel feature support. Forcing
`overlay2` under the guest's OverlayFS root failed with
`filesystem ... not supported as upperdir`. The successful run used
`/.droid/state/container-test/docker` directly on the ext4 state mount. For a
fresh daemon, a dedicated directory on that same mount can be configured as:

```json
{
  "data-root": "/.droid/state/docker",
  "storage-driver": "overlay2",
  "features": {"containerd-snapshotter": false}
}
```

These are daemon settings for the tested Docker version, to merge with the
installation's own configuration. Use an empty data-root and preserve/migrate
existing Docker data separately before changing a populated installation.
ReDroid `/data` also belongs on ext4-backed guest storage. Container packages
and these daemon settings are not preinstalled in the candidate image.

The nested Android test uses the locally cached ARM64
`redroid/redroid:15.0.0-latest` image ID
`sha256:81889852444606824d33eb0232fa5813bce3f0993afe871785120af0d7c991ad`.
It starts a guest-local privileged container with a new `/data`,
`androidboot.use_memfd=1`, `androidboot.redroid_gpu_mode=guest`, a 360x640
software display and 10 FPS. The outer QEMU container has no network, no extra
capabilities or host device passthrough. ReDroid therefore receives privileges
only within the disposable Ubuntu VM; Android AVF authorization is unchanged.

The first ReDroid run did **not** reach `sys.boot_completed=1`. It was stopped
for diagnosis after repeated Android watchdog resets. Offline ext4 journal
recovery and extraction of the newly created `/data/system/dropbox` and ANR
records identified `system_server` main-thread watchdog timeouts of 60–70 s.
CPU PSI `some avg10` was 75–91%, memory PSI was zero and I/O PSI was below 1%.
Main-thread snapshots progressed through SQLite/settings initialization,
`RoleService` and Binder service registration rather than remaining at one
call. CPU starvation in TCG with software rendering is the leading explanation;
these observations do not prove that every Binder/kernel path is correct.
Binder device allocation and Android's BPF loader had completed successfully.
The repeated watchdog/ANR timeouts are a failed full-boot check, not a ReDroid
success. A comparison run used the same kernel, fresh Android data, three
virtual CPUs and a 240x320, 1 FPS software display. It reproduced a 63 s
main-thread watchdog timeout, with CPU PSI 83.81%, memory PSI zero and I/O PSI
0.60%. Its ANR snapshots showed a Binder service lookup during notification
initialization and later runnable storage/user initialization, so they do not
establish a permanent Binder deadlock. Both runs were stopped after watchdog
failure to preserve their records. No watchdog behavior or AVF security policy
was changed.

Reducing display load and adding a virtual CPU did not make the full boot pass.
CPU starvation remains a supported hypothesis, not an isolated root-cause
proof. This host has no `/dev/kvm`, and no connected Android target exposes AVF.
A hardware-accelerated or physical AVF comparison is still required to distinguish
TCG performance from any remaining ReDroid/kernel compatibility issue. Do not
promote this diagnostic run into a ReDroid full-boot or physical AVF success.

## Primary references

- [Official Android kernel builds](https://source.android.com/docs/setup/build/building-kernels)
- [Exact common source](https://android.googlesource.com/kernel/common/+/f33ca06267f86f1974bd273c0cce78f614d6949e/)
- [Official Linux server target](https://android.googlesource.com/kernel/common-modules/virtual-device/+/refs/heads/android16-6.12/BUILD.bazel)
- [Kleaf config ordering](https://android.googlesource.com/kernel/build/+/refs/heads/main-kernel-2025/kleaf/docs/kernel_config.md)
- [Docker feature checker](https://github.com/moby/moby/blob/master/contrib/check-config.sh)
- [ReDroid memfd/software-rendering configuration](https://github.com/remote-android/redroid-doc/blob/master/README.md)
- [Android 15 watchdog timeout and SysRq behavior](https://github.com/aosp-mirror/platform_frameworks_base/blob/android15-release/services/core/java/com/android/server/Watchdog.java)
- [ReDroid heap/BinderFS requirements](https://github.com/remote-android/redroid-doc/blob/master/deploy/openeuler.md)
