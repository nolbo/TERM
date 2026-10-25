# AVF notes

## Interactive transport regression review

On 2026-10-24 the user reported delayed input echo and slow Codex CLI session loading even after updating the guest components. The slow-SSH comparison describes the perceived delay; it is not evidence from an external SSH connection. No privileged AVF target is attached to this development host, so the user's actual network, VM scheduling and screen latency remain unmeasured.

The reviewed release sources are 1.4.0 `9f9381c5637e`, 1.5.0 `d1a3e17cde13` and 1.5.1 `8f8fb58a933f`. Version 1.5.0 changed capable guests from direct session vsock to a host replay stream and Python guest relay; shared-folder SFTP uses the same replay implementation. Host replay headers used unbuffered primitive IO and ring copies performed modulo arithmetic per byte. These are concrete additional costs in the new normal IO path, including terminal redraw and shared-file workloads. The review found no change to the driver's CPU/memory/network configuration inputs between these releases. This source comparison does not prove equivalent device scheduling or Internet behavior.

Version 1.5.1 added a separate IO sender and independent deadline for each session request, plus bounded platform connection attempts and environment ownership handling. On the same ReDroid15 ARM64 device, an isolated transport returning an immediate RPC response measured request dispatch p50/p95 of 0.404/0.547 ms for the exact 1.5.0 client and 0.528/0.657 ms for 1.5.1. This identifies a small normal-path cost; it neither establishes the user's full delay nor rules out other 1.5.1 interactions. Cancellation/deadline protections remain intact.

The host fix buffers raw replay IO per link and flushes each complete frame immediately. Ring wrap uses at most two bulk copies, and receiving copies directly into the application buffer. Wire bytes, consumption credit, recovery budgets and operation ordering stay unchanged. No guest/image update is required for this fix; existing verified recovery images and installed data remain compatible.

The follow-up packet optimization reads session frames directly into one fresh payload array through `Protocol.read`, shared by the legacy and resumable transports. It uses the same magic/version/service/kind/reserved-field/size validation as the array decoder, rejects truncated input and consumes exactly one frame. No complete wire array is assembled and split again; received arrays remain independently owned rather than pooled across queued frames. A local JVM allocation probe for 64 KiB payloads measured 196,920 bytes/frame for the former join/decode path and 65,736 for direct stream decoding. This measures allocation overhead, not actual Android AVF latency.

SFTP responses reserve the length prefix in the existing packet-building buffer and write the complete packet once, followed immediately by flush. There is no extra full-packet clone or batch timer. A status-packet probe confirmed one application write instead of two and identical wire bytes. The 256 KiB body limit, partial-stream handling, per-request processing and error propagation are unchanged. Large packets still span bounded replay frames with consumption credit; neither packet combining nor stream decoding alters ACKs, heartbeats, recovery timeouts or replay authority. These changes require no new guest/image.

Follow-up validation passed 45 protocol tests, 429 runtime tests with one skipped, 68 image tests and 43 AVF tests, plus the canonical debug APK and instrumentation APK builds. The reconnect regression now uses 100,000-byte and 65,537-byte frames across socket replacement. Android MCP ran five checks on an isolated ReDroid15 ARM64 installation: fragmented native frame reads, blocked native read/write cancellation, real private-tree SFTP writes/rename/fsync and an open SFTP handle surviving raw-socket replacement after rename. All five passed. Actual privileged AVF behavior remains unmeasured; these are host/JNI/socket fixtures. The isolated test installation is removed after validation; installed user apps and environments are preserved.

Native Android socket-pair replay tests compared the exact published implementation with the fix: 128-byte echo p50/p95 changed from 0.322/0.406 ms to 0.172/0.240 ms. For 500 echoes, host raw writes changed from 6,506 to 1,001 and reads from 3,585 to 995. Linux Kotlin-to-existing-Python loopback tests with TCP_NODELAY measured 8 MiB duplex throughput of 65.3 MiB/s before and 148.0 MiB/s after; one-byte RTT p50 changed only from 0.281 to 0.261 ms. These local fixtures isolate transport overhead, not actual Android AVF vsock, Codex CLI, FUSE or Internet performance. They must not be presented as resolution of the user's device symptom.

The service also used input-triggered retry resets to launch recovery even when every AVF controller was healthy. The no-disconnect branch then ran temporary-Workspace verification and session snapshot refresh, each querying live owners. This behavior already existed in 1.4.0; adding the replay path can increase its cost. Scheduling now checks disconnected owners and environment demand before starting a session recovery worker, leaving healthy input free of these extra RPCs. Actual disconnects still trigger bounded recovery and publish a refreshed snapshot after successful attachment.

Input dispatch now uses a FIFO per registered Environment controller instead of one FIFO for every runtime. It pins both controller and session lifetimes at acceptance, checks them again before sending, and never resolves a queued packet against a replacement controller. The same controller shared by several sessions keeps one ordering lane; raw replay reconnects and re-registration of that same controller keep its identity. Actual controller replacement, session exit/termination or recreation rejects the old queued destination. A single limit of 256 active/pending packets bounds all lanes together. Empty lanes are released, service teardown discards pending input and cancels workers, and Native PTY writes use the existing I/O dispatcher.

Focused queue/registry checks and the canonical APK/runtime/image command with protocol/AVF tests passed: 594 unit tests passed and one was skipped. Coverage includes sibling progress while a writer is suspended, same-owner order and immutable bytes, aggregate capacity including in-flight input, error/cancellation isolation, shutdown, controller replacement and session-ID reuse. Android MCP passed two checks in an isolated ReDroid15 ARM64 package: a raw PTY deliberately left unread in Environment A while three input packets execute an ordered command in Environment B within five seconds, plus existing profile ownership and background wake lifetime. These fixtures do not establish privileged AVF device behavior. Recovery/heartbeat/credit limits and guest images are unchanged; no uncertain input is replayed into a new logical owner.

The next host change removes network CREATE/ATTACH/RESIZE waits from the shared terminal-state lock. Geometry operations and service terminal commands serialize per session, with a shared limit of 512 active/pending commands, so another session can process output, capture a checkpoint or resize while one owner is waiting. A successful matching protocol resize response produces an opaque host-only marker at its position in the incoming stream; Native/Rootless emit the equivalent marker under their output mutex. Emulator geometry changes at that marker before subsequent output. Controller, session-lifetime and state identities reject stale completion after replacement, exit, termination or ID reuse. Creation initializes local state behind an owner-specific gate before releasing initial output, and waits for AVF attachment outside the state lock. No wire, heartbeat, credit, timeout, retry or approved-image change is involved.

Validation passed 606 unit tests with one skipped across protocol, runtime, image and AVF modules; the debug APK and instrumentation APK built successfully. New checks cover delayed resize with continuing output and sibling resize/checkpoint progress, old/new geometry ordering, invalid responses, same-session serialization, failure recovery, late completion fencing, delayed creation/attachment and bounded queue shutdown. Two validation issues were corrected: a test reached configuration before the fixture's new state existed, and an existing device-test event switch needed the new host-only branch. These were test/harness issues, not device performance measurements.

Android MCP validation on an isolated ReDroid15 ARM64 package passed real Native PTY width/long-input editing, fullscreen VT modes and saved cursor restoration after background/recreation, and grace-period UI detach/checkpoint restoration with a live sibling session. The grace-period scenario initially failed before its terminal assertions because ActivityScenario remained STARTED instead of RESUMED; it passed when rerun alone (12.933 seconds). The first two scenarios passed on their initial run. The isolated packages were removed afterward. No privileged AVF device is available, so actual AVF latency, VM/background scheduling and real-vsock recovery remain unmeasured; passing host/unit/Native checks must not be described as proof of the user's symptom being resolved.

Focused tests cover immediate one-byte/full-frame flush, one raw write per frame, non-power-of-two ring wrap, full duplex credit and reconnection against the existing Python guest. Canonical APK/runtime/image checks and all protocol/AVF unit tests passed. Native Android cancellation tests passed; the temporary comparison fixture initially failed to enter the APK and then had an invalid JUnit return type, both corrected before its two successful comparisons. Historical implementation copies and the performance fixture stay outside committed product/test sources. Jev source export was previously rejected by automatic approval review, so this review uses local diffs, tests and measured results without retransmitting source.

## Background sharing failure review

A user running app 1.5.1 reported sharing recovery failure and AVF Environment termination after a long background interval. The supplied `share.control.reconnecting` event contained only `IllegalStateException` and no phase code. That record establishes a failed control attempt, not a specific rejected handshake, lease expiry or verified VM stop. It cannot establish the device's actual shutdown cause.

Source review found that the v2 guest monitor wrapped both startup and post-readiness work in the same VM-fatal `share.mount.failed` handler. A post-readiness operational exception therefore powered off the VM even when its guards and relay teardown could still establish safe isolation. The monitor now retires sharing while preserving the VM if the complete pinned guard set and actor shutdown succeed. Failed startup or isolation remains fatal, with no retry that relaxes a failed isolation proof. An injected post-readiness monitor exception, full lease expiry, incomplete guards and failed isolation are covered by regression tests. This fixes that source path; it does not prove that the user's device took it.

The host now records fixed failure phases, response status/EOF, retry attempt and observed control outage duration. Its local control watchdog reports idle duration before closing the stalled socket. Guest diagnostic records include fatal/nonfatal classification and OS errno; a later fatal failure replaces an earlier transient record so the actual termination cause is retained. Metadata still excludes paths, tokens, payloads and raw exception messages. The 60-second authority and replay limits, buffer credit, no-repeat mutation policy and legacy-v1 shutdown policy are unchanged. Long outages can still permanently disable sharing; this change does not promise recovery beyond that authority window.

Validation: the canonical debug APK/runtime/image checks plus protocol and AVF unit checks passed (607 passed, one skipped). All 146 Python image tests passed, including guest-package assembly checks. The initial Python run hit sandbox socket restrictions and was rerun with local socket access; test fixture timing and a missing test import were corrected. Android MCP found only ReDroid15 ARM64 with no virtualization feature, so actual privileged AVF, FUSE mount teardown and device background/Doze behavior remain unvalidated. The first feature query returned a nonzero exit without output; the feature-list fallback confirmed no virtualization entry. Jev source export remains unavailable under the earlier automatic approval rejection; review uses local diffs and actual tests.

The guest script changes require newly built and verified guest components/images and a stopped-Environment update under `AVF_UPDATES.md`. The source-only implementation did not change installed guests. The 1.5.2 release preparation promotes the verified recovery-20261024-r4 components and catalog; results are recorded in [the release validation](release-validation/1.5.2.md). Host diagnostic changes require a new app build; they cannot reconstruct missing details from a 1.5.1 log.

## Deployment constraint

The custom Linux VM path needs `MANAGE_VIRTUAL_MACHINE` and
`USE_CUSTOM_VIRTUAL_MACHINE`. These are privileged/signature permissions on the
relevant Android releases. Declaring them in a normal APK does not grant them.

## Packaged driver and platform boundary

New Debian, Ubuntu and Arch image production requires a rebuilt container-capable guest kernel;
the approved installed image is not changed by this source implementation.
Build, packaging, validation status and full-transition requirements are in
[AVF_CONTAINER_KERNEL.md](AVF_CONTAINER_KERNEL.md).

Ordinary APK builds include `AospAvfPlatformDriver` through `:aosp-avf-runtime`. `TerminalApplication` supplies the runtime provider while retaining Native as the default owner. Selecting AVF creates a separate backend only after checking Android 15+, the AVF feature, and both actual permission grants. The optional `android.system.virtualmachine` shared library is supplied by the device; compile-only API declarations are never APK runtime classes.

Custom-image configuration methods are hidden in AOSP and absent even from ordinary System SDK stubs. A compatible platform implementation and authorization to call those methods remain necessary in addition to the two permissions. Packaging the driver does not grant permissions or bypass API policy. Unsupported devices receive an explicit AVF error and continue using Native/Rootless. See `platform/aosp-avf-runtime/README.md` for ABI pinning, an exact OEM framework-jar override, and APK/device validation.

After the device/permission checks, backend creation allocates a custom-image builder before requesting installation or creating a VM. A linkage error here rejects startup immediately and explains both an incompatible ABI and non-SDK policy denial, retaining the original error as its cause. AOSP's pinned `VirtualMachineCustomImageConfig.Builder` has a no-argument constructor; `No direct method <init>()V` alone does not prove a different constructor, since ART also reports blocked hidden methods as missing methods. ADB grants for the two VM permissions do not by themselves authorize these hidden APIs. Use the target framework's actual signatures and the app's hidden-API logcat entries to distinguish the two cases; see `DEPLOYMENT.md`.

The AVF diagnostic Activity independently checks Android 15+, system feature, both actual permission grants, custom-image API access/signatures, VirtualMachineManager availability and the packaged driver ABI. It reuses the builder access and manager lookup used by startup without constructing a backend/driver or creating/running/altering a VM. Missing prerequisites leave API checks unknown; a missing permission never establishes API absence. Linkage/reflection errors remain unknown and explain both hidden API restrictions and possible ABI incompatibility. All seven verified conditions are needed for a satisfied basic-VM result, which is not a VM boot certificate. A separate optional storage-sharing check allocates the same `SharedPath` and calls `addSharedPath` as startup, without building a VM config, exporting files, or starting a server. A sharing failure does not invalidate basic VM support; no sharing probe runs when the basic checks fail. Actual mounts still require target-device validation.

Sharing linkage failures explicitly name the internal-storage/shared-directory API and retain the original cause. Installation errors retain up to 2048 characters in WorkManager output (with an ellipsis for longer errors), and the monitor does not shorten them again. The installation card displays selectable text and an error-copy action. This preserves constructor descriptors and class names formerly lost at the 240-character boundary; it does not adapt an unknown OEM ABI or change hidden-API enforcement.

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
the separate explicit stopped-VM procedure in [AVF_UPDATES.md](AVF_UPDATES.md).

New producer manifests declare optional `aptInitializationVersion=1`, which identifies the fixed host-only guest APT initializer. Online installs of those verified images refresh the full indexes through `:vmhost` before reporting completion and preserve the disks for retry on failure. Older manifests omit this field and retain file-only installation; unknown initialization versions are rejected. Existing installed images are not migrated.

Image download is a one-time WorkManager task, not a VM owner. It accepts only
a release ID from a bundled/OEM catalog; the catalog pins an HTTPS manifest URL,
its SHA-256 hash, and an artifact-size limit. The image factory produces the catalog. Ordinary builds embed the reviewed `image/catalog-approved.properties` when present, and `-PavfImageCatalog=...` overrides it for an isolated product/candidate build. Without either input the source build retains an empty catalog. Default approval requires successful unauthenticated public downloads and remains a reviewed source change. HTTPS asset redirects are bounded and cannot downgrade to HTTP; both compressed transfers and decompressed artifacts have size limits. The pinned manifest and uncompressed artifact hashes remain mandatory.

After saving an AVF profile with new storage, the editor requests installation only if the user confirms it. The same explicit action is available later in saved profile settings. WorkManager's multiprocess API runs the installer in the configured main application process with a data-sync foreground notification. Installation does not boot a VM or allocate a TAP interface. A session can wait for an already approved installation and continue after completion; session startup never requests a new download. Every profile sharing the storage observes its persisted progress inline. Installation status reflects image files rather than setup preferences or VM readiness. Existing images are never overwritten, including invalid installations. Both the public and AOSP application classes provide the same main-process WorkManager configuration.

## Guest DHCP and DNS

Factory images install `/etc/systemd/network/10-term-virtio.network`, matching `Driver=virtio_net` instead of a PCI-dependent interface name. `systemd-networkd` obtains addresses, routes and DNS through DHCP; DHCP hostname changes are disabled to preserve the driver's `localhost` hostname. The producer requires and enables both `systemd-networkd` and `systemd-resolved`. `/etc/resolv.conf` points to `/run/systemd/resolve/stub-resolv.conf`, so libc and programs reading that file use the same guest resolver and DHCP-provided DNS. No build-container resolver or fixed public DNS address is shipped.

The previously approved `avf-debian-20261004` image retained a regular Docker-generated `/etc/resolv.conf` pointing to `169.254.169.254`, and did not install a TERM network rule for the virtio NIC. A reported `enp0s8` stayed down with no address or default route despite both services being active. Thus replacing DNS alone does not repair the missing DHCP configuration. The producer seals the resolver symlink after `virt-customize` exits and checks the configuration on both offline QEMU boots. Those boots still use `-nic none`; their gate checks installed policy, service enablement and the final symlink, not live DHCP, DNS or Android tethering.

This change applies to newly produced images; an APK update does not rewrite an installed image or its persistent writable disk. Existing VM repair commands are documented in [the Korean user guide](USER_GUIDE.ko.md#avf에서-apt가-dns-오류로-실패할-때). If DHCP cannot supply an address or route after that repair, inspect Android TAP/tethering on the actual device. Vsock terminal access does not establish Internet connectivity.

## Fixed TAP networking on some products

At the pinned AOSP ABI revision, [VirtualizationService ignores the requested TAP suffix and uses `fixed`](https://android.googlesource.com/platform/packages/modules/Virtualization/+/175a51b30123fa6b02b541f1969665708f7ec2c3/android/virtualizationservice/src/aidl.rs#526); its TODO requires a bridge before using per-VM names. The custom-image Java API exposes only the network enable boolean, without an app-controlled TAP name. A denial involving `avf_tap_fixed`, `TUNSETIFF` and `EACCES` when a second independently stored VM starts is consistent with this platform restriction, rather than a profile/storage identity collision. The driver adds an explanation and keeps the original failure as its cause. Other permissions, image and VM errors remain unchanged.

Concurrent internet-connected VMs on such a product require platform TAP/bridge and tethering support. Existing VMs and requested network settings remain unchanged when creation fails. The current driver cannot supply distinct TAP names through the custom-image API; an APK-side VM rename or additional VM permission grant does not change the fixed-suffix service implementation. Vsock session access remains separate from the TAP device, but an offline workaround does not satisfy the requirement that both VMs have internet. Profiles sharing one storage use the same VM and do not request another TAP just to create another PTY. Successful multi-VM networking still requires validation on the target product; an unsupported emulator cannot establish it.

## Live shared directories

AVF connected folders require a verified `shareBrokerVersion=1` image and a non-protected VM. The app opens a boot-owned control stream and one SFTP v3 stream per selected UUID through host-initiated vsock 40506. The guest accepts host CID 2 only and runs passive SSHFS as UID/GID 1000 at `/mnt/term-shares/<name>`. Each host server pins one app-private UUID directory; guest messages never choose a host root. Parent walks use no-follow directory capabilities, content/metadata operations reject special files and hardlinked leaves, and symlinks are returned literally for guest-side resolution. No connected-folder `SharedPath` or index export is created. Optional whole-`/sdcard` virtiofs remains a separate capability.

Private sharing needs the normal authorized custom-image and host-initiated vsock APIs, but no connected-folder SharedPath API or all-files grant. Protected VMs remain unsupported. SSHFS/FUSE runs as guest `droid`; a small root coordinator accepts the fixed manifest, starts fixed client arguments, verifies mount readiness and fails the VM closed on owner loss. It never executes host-provided commands. Socket, request and handle budgets bound resource use. Actual FUSE/vsock startup, multi-VM access and teardown require a compatible AVF target.

Existing public-backed folders retain their current data until an explicit Save confirms restoration to app-private storage. This one-time copy requires the actual all-files grant and proven idle sessions, persisted VM owners, file jobs and same-UID writers under the preparation reservation. It copies and rehashes the latest public tree, stages `.private-<UUID>`, retains the old private tree as `.original-<UUID>`, then publishes private metadata last. A synced `.shared-folder-private-restores/<UUID>` journal permits retrying only unpublished private destinations. The public source remains intact; missing sources, links and special files fail rather than selecting the stale private backup. Limits are 2 GiB, 100,000 entries and 64 levels. Once restored, ordinary sharing and deletion need no storage grant; folder deletion removes its private data/backup and leaves the retained public source.

A service-side `.../virtualizationservice/<CID>/s0` timeout means the first virtiofs helper did not publish its socket; the precise physical-device denial still requires device evidence. The app-private index is incompatible with stock Android 17 policy. The new broker avoids that helper for connected folders entirely. Whole-`/sdcard` export still uses platform virtiofs and has its own platform requirements. The 20261020-r1 default images include the broker. Older installed disks remain unchanged, and new image pins reach users only through an APK containing the updated catalog.

New images also declare `androidStorageVersion=1`. With the existing common all-files grant and saved Environment internal-storage choice enabled, non-protected VM boots export Android's primary shared-storage root through the fixed `term-storage` export and mount it at `/sdcard` with `nodev,nosuid,noexec`. The Environment Permissions section and new-Environment creation draft expose the choice. New Environments default off; old metadata without the field preserves prior behavior. Changing the choice requires VM stop/start and does not hot-revoke a live export. Broker-only boots need no storage permission. Old persisted public-folder leases retain their original storage permission dependency until stopped. AppOps revocation stops only recorded storage-sharing VMs, including persistent instances after owner restart; boot and revocation share a process-wide gate and stop failures retain records. Actual FUSE permissions, blocked Android paths, open-file revocation, multi-VM cleanup and SELinux behavior still need validation on the matching target.

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

The producer installs the matched container-capable kernel/module bundle and generates an initramfs-tools ramdisk. New format-v2 Debian, Ubuntu and Arch images boot a complete writable EXT4 root on vdb (`home.img`), with `root=/dev/vdb rootfstype=ext4 rw console=hvc0 systemd.hostname=localhost`. The independently verified RO vda filesystem is mounted at `/.droid/lower` as the update baseline; `/.droid/state` is an ordinary directory on the root filesystem. The config disk remains read-only at `/etc/droid-config`. Installed format-v1 images still boot their existing OverlayFS initrd with `vda ro`; only an explicit full-image update transitions them. See `AVF_DISTRIBUTIONS.md` and `IMAGE_RELEASE.md` for compatibility and the two-boot QEMU gate.

The session daemon runs as `droid`, whose home and default AVF session directory are `/home/droid`. The producer creates and verifies this account home instead of a separate `/home/user` directory. Existing factory images already contain the `droid` home and need no replacement. On first registry access after the app upgrade, saved AVF profiles with the old `/home/user` default are migrated once; other custom directories and later explicit `/home/user` choices are preserved. This affects new sessions only and neither moves existing `/home/user` files nor changes live launch snapshots.

Newly produced images also give this single-user guest account passwordless sudo while locking password login. `/etc/sudoers.d/90-term-droid` is root-owned mode 0440 and checked by `visudo`; the producer requires the base image's sudo, APT and dpkg tools rather than silently producing an incomplete guest. `droid-sessiond` explicitly uses `NoNewPrivileges=no` to allow setuid sudo in its shells. The offline image gate runs as `droid`, proves sudo reaches UID 0, updates APT's normal list/cache directories from a temporary local repository, installs and removes a probe package, and checks this on both EXT4-root boots. It does not change the guest's configured upstream sources or certify Android TAP networking, DNS, or live upstream downloads. This policy is baked into newly generated images only; APK updates do not retrofit existing system images or change the approved catalog.

AVF `getOrCreate` retains an existing VM configuration. The driver calls `setConfig` only on stopped instances before starting them, so existing installations also receive the short `localhost` guest hostname on their next boot. AOSP checks configuration compatibility; incompatible identity changes fail without deleting or recreating the VM. Running guests are reconnected without changing their configuration. Persistent VM names, including `droid-default` and independent storage IDs, remain unchanged. Explicit image changes require the stopped-VM migration and rollback procedure in [AVF_UPDATES.md](AVF_UPDATES.md); ordinary startup never replaces installed disks.

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

## Resource-cost corrections

CPU settings now match the platform's actual single/host-topology ABI. Memory defaults can be automatic while manual values survive. Replay rings grow from zero payload storage rather than allocating 512KiB immediately; a small host stream uses a 4KiB ring. Guest replay avoids bytearray prefix deletion and intermediate receive/slice copies. Reconnection watchers use close events, and unchanged guest port scans back off to ten seconds. The guest dispatcher handles fragmented frames, blocked socket writes and queued PTY input without blocking sibling output or resource queries. Private-share direct-I/O and zero-cache policy are retained; a redundant SFTP READ clone is removed.

Memory diagnostics requests fixed aggregate guest metrics without creating sessions or booting environments. It reports guest memory/swap, CPU availability, load and memory PSI separately from app PSS. Legacy guests remain supported without repeated metric probes. The guest changes require an explicit verified component/image update; installed data is preserved.

A proposed metadata-based startup hash cache was rejected during security review. Full SHA-256 checks remain authoritative on every preparation; verification reuses a single buffer and uses direct hex encoding. Manifest reads are bounded to 64KiB. Cold-start disk/hash work and real VM kernel memory remain inherent costs, not claimed eliminated. No privileged AVF target is attached, so actual device CPU/thermal/latency improvements require a matching device measurement.
