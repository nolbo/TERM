# AVF Linux container kernel

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
That candidate-only run skipped publication and notification. The image-only
publication reuses its exact verified bytes; see the committed
[Korean release notes](release-notes/avf-ubuntu-containers-20261021-r1.md).
The default catalog remains unchanged while broader physical AVF acceptance is
pending.

These are QEMU results, **not physical Android AVF results**. The connected
ReDroid Android 15 target exposes neither the AVF feature nor its VM tool.
Later user-supplied AVF boot and ReDroid results are recorded in the
AArch64-only compatibility section below. An LXC container and device-level
network forwarding remain unverified. An additional QEMU run passed Docker
`hello-world` with ext4-backed
`overlay2`. The final isolated ReDroid comparison passed Android boot, core
services, a resumed Settings activity and an inspected Settings screenshot.
Earlier comparisons failed with watchdog resets, black output or Settings ANRs;
details and the limits of the successful comparison are recorded below.

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
Docker/LXC/ReDroid packages, CMA heap or Swarm/IPVS/VXLAN drivers are added.
Existing baseline ashmem options are retained. Every distribution installs
iproute2/iptables for the probe. Kernel assets remain image
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

Debian, Ubuntu and Arch production reject a missing/invalid bundle before Docker/downloads. The
factory replaces **both** Google kernel/module inputs. Inside the existing
isolated appliance, the module-tree release must match the new kernel metadata,
then depmod and the existing overlay initramfs generation run. Final config and
build identity are installed in the immutable system's `/usr/share/term/`.
The existing five-artifact manifest hashes the actual new kernel and generated
boot ramdisk. No host mounts or privileged build container are introduced.

The workflow builds the same kernel recipe on x86_64 before ARM64 image production
for every distribution and refuses to reuse old candidates without matching
kernel/probe provenance. Both nft and legacy iptables frontends are required in
each guest. On Arch, the signed `iptables` package supplies both; its default
frontend uses nft ([upstream package change](https://archlinux.org/news/iptables-now-defaults-to-the-nft-backend/)).
The new requirements apply to newly produced images; legacy published images
remain readable and installed environments are preserved. Publication, public
download verification, catalog promotion and signing follow
[IMAGE_RELEASE.md](IMAGE_RELEASE.md). Different
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
The host's containerd image store reports the manifest digest as its image ID;
the guest's classic store reports config ID
`sha256:c9c5cfdc4671d32340b5305f623e286c29da971b5f22d382e78829c9282a16d0`.
Both report the same ARM64 root filesystem layer
`sha256:34d4e23df9cf7e91546ce4522d21f082759a29112e83dd0420e1651754a90476`.
It starts a guest-local privileged container with a new `/data`,
`androidboot.use_memfd=1` and `androidboot.redroid_gpu_mode=guest`. The initial
comparison used a 360x640 software display; the final profile uses 240x320 at
10 FPS. The outer QEMU container has no network, no extra
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

Those changes alone did not make the full boot pass. Later comparisons isolated
additional issues without modifying the kernel or disabling Android watchdogs:

- Android 15 SurfaceFlinger waits for eager shader-cache priming at boot.
  The optional launcher disables only `service.sf.prime_shader_cache` in a
  read-only, per-container copy of the original `/system/build.prop`.
- QEMU 10's standard TCG `max` CPU is substantially faster here than QEMU 7.2.
  Priming disabled on QEMU 7.2, and stock priming on QEMU 10, both still failed.
  Their combination reached boot with two vCPUs, but black output and a later
  Settings ANR in SwiftShader/ANGLE presentation prevented a GUI success claim.
- A diagnostic-channel bug truncated large virtio-serial writes at 32 KiB.
  The test control now handles partial writes and kills timed-out command
  process groups. This was a test-transport defect, not proof of a Binder stall.
- The final cold-data run used QEMU 10.0.13, four vCPUs, 4 GiB guest memory,
  240x320 at 10 FPS, priming disabled and 240 seconds of settling after boot.
  Android boot completed in 242.21 seconds. Activity/package/display services,
  Settings launch and final resumed activity passed. The 307,216-byte raw frame
  had 654 RGB colors, and the PNG was inspected: it displays Settings and its
  Network & internet row. Total test time was 560.35 seconds. No full
  `system_server_watchdog` record was present; a pre-watchdog warning remained.

This establishes one cold boot and a rendered Settings screen under the stated
software-emulation profile. It does not establish long-running stability, a warm
reboot of that final profile, or an isolated root cause for every earlier ANR.
This host has no `/dev/kvm`, and no connected Android target exposes AVF.
Physical AVF performance, networking, hardware/security behavior and ReDroid
stability remain to be checked using the [device test guide](REDROID_TEST.ko.md).

## Reproducing the isolated ReDroid check

`tests/integration/redroid_probe.py` reproduces the additional container test;
it is separate from the mandatory factory systemd/vsock acceptance boots.
It verifies candidate metadata and hashes the actual kernel, boot ramdisk,
system and config disks before booting. It always creates a new work directory
and clones the fixture state. The candidate disks stay read-only; the installed
environment and its home disk are not opened.

Build the small host test appliance once:

```sh
docker build -t term-redroid-probe - < tests/integration/redroid-probe.Dockerfile
```

Provide the eight unpacked official **ARM64** Docker static binaries and
`docker save` archives of ARM64 `hello-world:latest` and
`redroid/redroid:15.0.0-latest`. The tested Docker version is 29.1.3; its official
`docker-29.1.3.tgz` ARM64 archive SHA-256 is
`2219f3ac48727d8c5546b13d5d271a58856188ecb8ff1f71f9e2f9aefde3ea76`.
Preserve the ReDroid image identity above: the mutable `latest` tag alone does
not identify the tested image. Importing these fixtures requires additional
disk space and can take considerably longer under TCG than an ordinary boot.

```sh
python3 tests/integration/redroid_probe.py \
  --image-dir image/build/container-candidate-8de06c0 \
  --work-dir image/build/redroid-fixture --tool-image term-redroid-probe \
  --docker-bin /path/to/arm64/docker \
  --redroid-image redroid/redroid:15.0.0-latest \
  --hello-archive /path/to/hello-world.tar \
  --redroid-archive /path/to/redroid.tar --prepare-only --sudo

python3 tests/integration/redroid_probe.py \
  --image-dir image/build/container-candidate-8de06c0 \
  --fixture-state image/build/redroid-fixture/state.img \
  --work-dir image/build/redroid-cold --tool-image term-redroid-probe \
  --redroid-image redroid/redroid:15.0.0-latest \
  --data-name redroid-check --cpus 4 --fps 10 --settle-seconds 240 --sudo
```

The default diagnostic launch disables SurfaceFlinger's eager shader-cache
priming; `--prime-shader-cache` provides the stock-setting comparison. It keeps
the standard QEMU `max` CPU, wall clock and Android watchdog behavior. The
guest-only `image/scripts/launch-redroid.py` reads `/system/build.prop` from a
stopped inspection container, preserves its other properties and imports, and
binds the modified copy read-only into the new Android container. It requires
the verified running container kernel, an ext4-backed Docker data-root and
Android data directory, a local Docker Unix socket and a locally cached image.
It refuses to replace an existing named container. The helper and container
packages are **not preinstalled** in the candidate image.

The test records `result.json`, console output and a PNG. It requires
`sys.boot_completed=1`, activity/package/display services, a successful Settings
activity launch, final resumed Settings activity, a complete non-flat raw frame
and no full `system_server_watchdog` record. A PNG header is not proof of a
rendered Settings UI: inspect the captured screen separately. For a persisted
data reboot, use the cold run's `state.img` as the next fixture and the same
`--data-name`, with a new work directory. Pre-watchdog warnings are recorded
separately and do not count as full watchdog resets.

The private structured-argv diagnostic channel and replacement test PID 1 are
installed only in that independent state disk and require the test boot flag.
They are not release-image payloads or production guest RPC endpoints. The outer
appliance drops all capabilities and has no network or device passthrough;
ReDroid's privileged mode applies only inside this disposable VM. This does not
change Android-host capabilities, AVF protection, kernel hardening or installed
image hashes. The probe never publishes or changes an approved catalog.

## AArch64-only AVF compatibility

The earlier QEMU `max` comparison does not establish AArch32 availability on a
physical AVF virtual CPU. The released kernel already has `CONFIG_COMPAT=y` and
`CONFIG_COMPAT_BINFMT_ELF=y`, but those options cannot provide a CPU execution
mode that the guest does not expose. A mixed-ABI Android image can fail before
boot when a required 32-bit executable, such as `boringssl_self_test32_vendor`,
cannot run. Android init's `reboot_on_failure` then requests a container reboot.
An exit code alone, or missing cgroup files during shutdown, does not establish
the initiating failure; inspect the earlier guest kernel log.

The launcher now defaults to the official Android 15 ARM64 `64only` manifest
`sha256:dc2024a999dd0acb1112a23cddc9537ec8cb60a0dbe5a87ce9fbd75a2f5e94d2`
from `redroid/redroid:15.0.0_64only-latest`. Its rootfs layer is
`sha256:a0ddf7bcfc59d8dbbb796d2d6202d5135a365deda42859e081daae598ceed342`.
This uses the upstream 64-bit product and its security self-tests, rather than
editing init's failure policy. It does not support 32-bit apps/native libraries.
Use a new container name and new Android data directory, preserving the previous
mixed-ABI container and data. No kernel, immutable Ubuntu image, catalog or APK
replacement is required for this image selection.

The offline probe accepts `--redroid-image` to select the image imported from
the fixture archive. It records guest CPU execution modes and Android ABI
properties in addition to the existing boot, services, watchdog and Settings
checks. Its QEMU `max` CPU remains a mixed-mode test. The older commands above
explicitly select the mixed-ABI image to preserve their original test.

A separate `--prepare-only` run with the released kernel and QEMU 10.0.13
passed Docker `hello-world` and imported the 64only archive into the guest's
classic image store. The selected image reported config ID
`sha256:3a20877461836b2c22092660a4bcf09ffac0528c324e74237ce099d9e9262191`
and the rootfs layer above. Its result recorded `fixturePrepared=true` and
`passed=true`; it did not start Android and is only an image-selection/import
check, not another ReDroid boot result.

On 2026-10-22, the device tester reported `CONFIG_COMPAT=y`,
`CONFIG_COMPAT_BINFMT_ELF=y` and CPU mode `64-bit` in the AVF guest. The original
mixed-ABI image stopped after its 32-bit BoringSSL program returned an exec
format error. With the pinned 64only image and a fresh container/data path,
the same guest reported `Running=true` and `sys.boot_completed=1`. The tester
also confirmed that activity/package/display service checks and Settings launch
completed normally. This is user-supplied device evidence; no direct agent
connection was available, and no device screenshot was independently inspected.
Long-term stability and reboot remain unverified.
An additional local AArch64-only QEMU model attempt lost its diagnostic
connection before guest readiness and supplies no successful runtime evidence.

Current device instructions and immutable helper downloads are in
[REDROID_TEST.ko.md](REDROID_TEST.ko.md).

## Debian and Arch build validation (2026-10-22)

Producer commit `ab252f815438efd323ea29b6d1e1b8e4823da955` extends the required
kernel/module bundle and container gates to all three distributions. Both new
bundles passed validation of the Image's embedded config, module ramdisk and
build metadata. Their final config is byte-identical to the verified Ubuntu
config, SHA-256 `06d4c21e1beab6461816d6f19b1d2aab18743a517dc908755d183ad927128c21`.
The rebuilt Image hashes differ and are pinned separately:

| Distribution | Producer run | Image SHA-256 |
| --- | --- | --- |
| Debian | [39692316459](https://github.com/nolbo/TERM/actions/runs/39692316459) | `f673780210f4b9c80a125b8b745140bf35f5a9f91903f0f1df05ce11722da0e7` |
| Arch Linux ARM | [39692322631](https://github.com/nolbo/TERM/actions/runs/39692322631) | `cdf39ec3f26e631df705382794950c5ce4c3c4116e99b6ef7d9f2b263c702a74` |

Each image passed both offline container-feature boots and both normal
systemd/vsock boots, including the session-list RPC, package initializer,
private-share read/write and owner-loss shutdown checks. The image provenance
matches the separately downloaded kernel bundle. These are real QEMU guest
results; they do not establish physical Debian/Arch AVF behavior, a full
Docker/LXC/ReDroid workload, external network forwarding or long-term stability.
The earlier physical Ubuntu result remains specific to that tested environment.

Both test releases passed anonymous public download verification of all five
compressed image artifacts against their manifest hashes. The Debian manifest
SHA-256 is `0d922960ef8091293ad0bc3af81586c7a8475da628be982cd66a2c3e66af0ab1`;
the Arch manifest SHA-256 is
`c1e746fb76d06579f7e74beb5c48560eae5821a087da7299e018e947bc0c5a92`.
The combined test APK pins these verified manifests. The default approved
catalog was unchanged for that test handoff; the later app-channel promotion is
recorded in [the 1.3.0 release validation](release-validation/1.3.0.md).

The expanded Python image suite passed 102 tests. The canonical Debug build,
runtime unit tests and image tests also passed with a candidate catalog containing
all three container images and the existing pinned accelerated Rootless payload.
The combined APK was built at `27d5791d6b21207ce7cd2c1f8e1648a002d64c0e` and
verified to embed the exact catalog, AVF driver and Rootless binaries. Its version
is `1.2.0+dev.214791050.27d5791d6b21-debug`; its signing certificate matches the
previous compatible Debug APK. Replacement installation and private test-data
preservation passed on the connected native ReDroid Android 15 target, which
does not provide AVF. APK size is 68,410,118 bytes, 281,412 bytes smaller than the
previous compatible APK. No guest kernel, Docker, LXC or ReDroid payload is
bundled into it.

Installation and per-distribution package commands are in
[REDROID_DISTRIBUTIONS_TEST.ko.md](REDROID_DISTRIBUTIONS_TEST.ko.md).

## Account migration correction in TERM 1.3.1

The `avf-{debian,ubuntu,archlinux}-maintenance-20261022-r1` images retain the
container configuration verified above and include the corrected account
merge for full image transitions. The former whole-file comparison rejected
independent group additions and different record order. The new merge preserves
numeric identities and aborts real conflicts. See [1.3.1 validation](release-validation/1.3.1.md)
for image provenance, publication pins and remaining device limitations. Previous
image and device results above remain historical evidence for their exact builds.

## Primary references

- [Official Android kernel builds](https://source.android.com/docs/setup/build/building-kernels)
- [Exact common source](https://android.googlesource.com/kernel/common/+/f33ca06267f86f1974bd273c0cce78f614d6949e/)
- [Official Linux server target](https://android.googlesource.com/kernel/common-modules/virtual-device/+/refs/heads/android16-6.12/BUILD.bazel)
- [Kleaf config ordering](https://android.googlesource.com/kernel/build/+/refs/heads/main-kernel-2025/kleaf/docs/kernel_config.md)
- [Docker feature checker](https://github.com/moby/moby/blob/master/contrib/check-config.sh)
- [ReDroid memfd/software-rendering configuration](https://github.com/remote-android/redroid-doc/blob/master/README.md)
- [ReDroid AArch64-only deployment](https://github.com/remote-android/redroid-doc/blob/master/deploy/README.md)
- [Android 15 watchdog timeout and SysRq behavior](https://github.com/aosp-mirror/platform_frameworks_base/blob/android15-release/services/core/java/com/android/server/Watchdog.java)
- [ReDroid heap/BinderFS requirements](https://github.com/remote-android/redroid-doc/blob/master/deploy/openeuler.md)
