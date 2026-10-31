# AVF Linux ARM64 image release procedure

End users do not build or repackage AVF images. The approved defaults for TERM 1.7.1 on 2026-10-31 are Debian `avf-debian-session-20261031-r4`, Ubuntu `avf-ubuntu-session-20261031-r2` and Arch `avf-archlinux-session-20261031-r2`. They include direct session transport, failure isolation, offline PTY progress and guest scheduling/output improvements. They declare `maintenanceVersion=1`, omit `sessionResumeVersion` and sharing capabilities, and retain the exact verified kernel used by unshared-r1, resources-r1, recovery-r4/r3 and ext4-20261022-r2. All three producers passed both normal-systemd reconnect/offline-progress boots, container probes, their catalog-bearing APK gate and anonymous full-download verification. Debian uses an independent TFT1 completion marker while the session socket remains closed, then requires EXITED/7 in the first LIST; this avoids assuming a fixed TCG throughput. [Validation and limitations](release-validation/1.7.1.md) records the hashes, CI and unsuccessful earlier probes. Korean notes: [Debian](release-notes/avf-debian-session-20261031-r4.md), [Ubuntu](release-notes/avf-ubuntu-session-20261031-r2.md), [Arch Linux ARM](release-notes/avf-archlinux-session-20261031-r2.md). Ordinary builds embed `image/catalog-approved.properties`; catalog promotion does not replace installed guests. Save work, stop sessions and stop the Environment before applying updates. Compatible unshared-r1/resources-r1/recovery-r4/r3/ext4-r2 Environments can use component updates because the kernel and disk format match; other installations follow [AVF_UPDATES.md](AVF_UPDATES.md). Existing folders and guest disks are preserved. Previous validation is preserved in [the 1.7.0 record](release-validation/1.7.0.md). These factory checks do not establish privileged Android AVF behavior; a normal APK cannot gain custom VM privileges through this installer.

New producer images remove stale APT package/source/translation indexes and partial/binary/package caches before the first smoke boot, preserving repository sources, signing keys, Release/InRelease metadata and installed dpkg state. `clean-avf-apt.sh` implements the image-finalization policy without depending on a particular APT `distclean` version and rejects symlinked cache paths. Both boot gates check the sealed lower layer is clean.

New producer manifests include the optional `aptInitializationVersion=1` field, covered by the catalog-pinned manifest hash. Online installation creates a separate pending APT initialization receipt only after full verification of an image declaring that version. Older manifests omit the field and retain file-only installation; unknown initialization versions are rejected. The installer requests `:vmhost` to boot the selected environment and call the host-only guest initializer. A successful full `apt-get update` with `Acquire::PDiffs=false` and `APT::Update::Error-Mode=any` records completion against the installed manifest; the writable root contains the new indexes, while immutable image hashes remain unchanged. Timeouts, cancellation and repository failures preserve the verified files and permit initialization-only retry. This needs an image containing `droid-apt-init.service` and the maintenance endpoint; old installed guests are not modified by an APK update, and older image releases cannot gain the new endpoint. Previously installed images and older releases such as `avf-debian-20261005` retain their previous installation behavior; the new default applies only to new installations.

## Automated producer

New maintenance-capable images/catalogs declare `maintenanceVersion=1`. Their
sealed system contains distribution-native TERM guest packages and fixed
maintenance/health scripts. Both offline boots must adopt the local package and
emit `TERM_AVF_GUEST_PACKAGE_OK`. Explicit component updates, image migration and
rollback follow [AVF_UPDATES.md](AVF_UPDATES.md). Old pins remain valid and are not
automatically replaced; approving/publishing a new image is still required.

The producer also performs two normal systemd boots with a real vhost-vsock
device, separately from the PID-1 filesystem/package fixture boots. On each boot
the host verifies an empty session-list RPC on 40500 and the package initializer's
listener on 40503. Both boots enable the private broker and a factory-only guest probe, including remounting the directory sealed by the previous boot. An unprivileged, network-isolated reference SFTP container exposes only a disposable fixture; the guest verifies real FUSE reads/writes, rename, symlink, fsync and live host changes. For broker v2, the producer replaces both raw share sockets while a real FUSE file handle remains open, then expires the owner lease and verifies that the VM/session stays live and an explicit unmount cannot expose writable shadow data. Both boots also reconnect a live session tunnel while its shell generates output. Provenance requires `privateShareReadWrite`, `privateShareOwnerLoss`, `privateShareReconnect`, `privateShareGuard` and `sessionResume`; v1 candidate reuse retains the original poweroff evidence contract. This is QEMU/Linux evidence, not Android AVF device validation.
Both boots share only a disposable copy of the writable disk;
the release disk is not modified. Enable the host `vhost_vsock` module before
production (`sudo modprobe vhost_vsock`); CI performs this preparation explicitly.
The probe runs on the host so the QEMU container retains its default seccomp
policy and requires neither privileged mode nor a host loop mount.

New producer manifests omit `virtiofsVersion`, `androidStorageVersion` and `shareBrokerVersion`. Guests have no TERM sharing mount/broker units or sessiond sharing drop-ins. Offline boots require `TERM_AVF_NO_SHARES_OK`; normal-systemd boots still verify terminal RPC, package initialization and session recovery. `--require-normal-boot` is mandatory in the workflow. Existing approved catalogs/images are unchanged until an authorized release.

The repository's `Build approved AVF Linux image` workflow automatically prepares a candidate when image, guest, or related verification files are pushed to `dev`; promoting only `image/catalog-approved.properties` does not rebuild an unchanged producer. It can also be dispatched on `dev` with an explicit immutable release ID. An ARM64 runner builds the guest binaries, downloads Google's official AVF Debian archive, rejects unsafe archive entries, installs the TERM services and CLI, creates the three disks, rebuilds the matching initrd and creates the manifest and pinned catalog. It records the upstream archive SHA-256 and repository commit in `provenance.json`. An optional reviewed source SHA-256 rejects upstream changes. Unsupported archive formats or missing overlay kernel support fail instead of producing an apparently usable image.

Unchanged kernel recipes automatically reuse a validated bundle across Debian,
Ubuntu and Arch builds, skipping the x86_64 source sync and Kleaf compile. Exact
cache keys, validation, cache misses and the `rebuild_kernel` escape hatch are
documented in [AVF_CONTAINER_KERNEL.md](AVF_CONTAINER_KERNEL.md). Image assembly,
both boot gates and the catalog-bearing APK gate still run for each new image.
Final packaging hashes raw artifacts while compressing them, eliminating a
separate full raw-disk scan. At most two gzip workers run at once, with the
largest disks scheduled first. Gzip remains deterministic (level 1, no filename,
zero timestamp); artifact names, manifest hashes and download format are unchanged
for the same input bytes. Workers stream bounded chunks, and any failure prevents
the staged release from being promoted.

The dependent APK job receives the factory metadata, verifies its source commit and the manifest hash supplied by the producer job, and builds a debug APK containing that exact catalog. It checks the embedded catalog, AVF driver, and Rootless payload before uploading the candidate APK. Debug variants use the timestamp/state allocator in `tools/updates/debug-version-code.py`; the supplied canonical release sequence is only a minimum. This review APK is not a separate app channel release. Candidate production alone does not publish image assets or make their URLs usable.

The default CI Debug signing key is generated on that runner. A higher version
code does not make its APK an update for a Debug installation signed elsewhere.
Before recommending an in-place update, compare package ID, version code and
signing certificate with the installed APK. For an authorized test handoff, the
verified APK may be re-signed locally with the retained matching Debug key.
Verify the new signature and equality of every non-signature ZIP entry, then test
replacement installation and private-data preservation on a suitable target.
Publish it under a new filename with its checksum; preserve the original artifact
and image assets. Do not upload the private signing key or replace installed apps
by uninstalling them. See [the Debug update contract](APP_UPDATES.md#debug-installations).

Filesystem parsing and guest configuration run in a libguestfs appliance inside an unprivileged container, without host mounts or a writable source checkout. Debian's signed `python3`, `iproute2` and `iptables` dependencies are provisioned against the actual upstream dpkg state in a separate network-enabled appliance/container phase. Ubuntu/Arch install their matching packages during their existing networked rootfs preparation. Final assembly and both boot checks remain network-isolated. No packages are installed on the host. Production requires Docker, an ARM64 host (or configured emulation), `/dev/vhost-vsock` for normal-boot verification and at least 24 GiB free plus Docker storage. Offline boot tests verify persistence, guest user/package configuration and installed sharing prerequisites. The separate normal-systemd probe verifies real Linux FUSE/vsock I/O; neither gate proves Android AVF behavior.

The producer configures `/home/droid`, locks password login and installs the validated root-owned sudoers policy for guest administration. It requires sudo, APT and dpkg tools from the upstream base and fails if they are absent or unusable. The new session unit permits sudo by setting `NoNewPrivileges=no`, while continuing to run as `droid`. These changes apply to newly produced images; installing a new APK does not update an existing guest's service/account policy. Build and publish a reviewed image candidate and explicitly approve its catalog before ordinary new installations can receive this setup.

The appliance uses the direct backend with `LIBGUESTFS_BACKEND_SETTINGS=force_tcg`, so hosted ARM64 runners do not require KVM. Without this setting, libguestfs can detect host KVM support while the container has no KVM device and select an unusable host GIC configuration. Debug/trace output is enabled to report appliance failures. Guest build failures terminate the factory immediately.

The producer requires the guest's `systemd-networkd` and `systemd-resolved`, installs a DHCP rule matching the `virtio_net` driver, and enables both services. It replaces the build-container `/etc/resolv.conf` with the guest resolver's `/run/systemd/resolve/stub-resolv.conf` symlink after `virt-customize` has finished, so temporary appliance resolver handling cannot restore the old file into the sealed image. Both boots require `TERM_AVF_NETWORK_CONFIG_OK`, checking the final symlink, the virtio DHCP rule, installed binaries and each service's enablement.

The direct-kernel smoke VM explicitly disables QEMU's default NIC with `-nic none`. It requires no network access or network boot ROM; the container can retain its minimal QEMU package set. The network configuration gate does not exercise a live NIC, DHCP lease, DNS lookup or Internet download. Android TAP/tethering and an upstream `apt-get update -o APT::Update::Error-Mode=any` still need a supported AVF target. Newly generated images receive this policy; existing installations and the approved catalog are not changed by modifying the producer.

Google's current direct-boot rootfs does not contain `/boot/vmlinuz-*` or `/lib/modules`; its paired initrd contains the matching kernel modules without a userspace boot script. New Debian, Ubuntu and Arch production requires the rebuilt container-kernel bundle described in [AVF_CONTAINER_KERNEL.md](AVF_CONTAINER_KERNEL.md), replacing the paired kernel/module inputs together. The producer restores the single module tree inside the isolated guest, verifies overlay support through its module metadata, and generates an initramfs-tools ramdisk for the complete EXT4 root on vdb. OverlayFS remains available for containers and mounting copied legacy-v1 state during maintenance; it is not the new guest root filesystem. It rejects absent, ambiguous or mismatched module releases. Every distribution requires both container feature probes; existing approved images remain unchanged until new candidates pass production and publication. The system image grows to at least 6 GiB inside the appliance to hold modules, the generated initrd, and TERM services; larger inputs are never truncated. The two-boot gate remains mandatory to establish that this kernel and ramdisk actually boot and retain EXT4-root writes. The container gate also mounts OverlayFS with upper/work directories under the actual EXT4-backed `/var/lib`, proving the backing-filesystem requirement without installing Docker.

For local product development, first build and verify the kernel bundle using
[AVF_CONTAINER_KERNEL.md](AVF_CONTAINER_KERNEL.md), then run the same image pipeline:

```sh
python3 image/scripts/build-avf-image.py \
  --kernel-bundle image/build/kernel-bundle \
  --release-id avf-debian-1 \
  --base-uri https://github.com/nolbo/TERM/releases/download/avf-debian-1/ \
  --output image/build/approved
```

Use an immutable release ID consistently in the URI and command. This is a producer command, not an end-user setup instruction. The producer refuses to overwrite an existing release directory. The six original files remain available for optional offline installation; the workflow uploads gzip-compressed boot/disk files, `manifest.properties`, `catalog.properties`, `provenance.json` and `SHA256SUMS` as its artifact. Format-v2 `home.img` contains the complete writable EXT4 root, including packages, home and container data. The factory copies the sealed system filesystem into an independent root disk, assigns a fresh filesystem UUID and adds 8 GiB beyond the seed capacity (normally a 14 GiB root), preserving the old writable-state budget. After the disposable two-boot probes it recreates this root from the sealed seed, removing every test write before release hashes are generated. Old APKs reject v2; approved v1 images and existing installations remain compatible with the new host. The production APT initialization probe uses only an offline fixture; it verifies failed repository handling, a successful full refresh and idempotent retry, then removes its completion receipt. A real networked AVF installation must additionally verify DNS, strict upstream index refresh, cancellation/retry and sibling-session preservation. The initial filesystem is reset after the two-boot test, before checksums are generated.

## Publication and product build

An app release or prerelease deployment request authorizes any required image production, verification, publication and catalog promotion under [AGENTS.md](../AGENTS.md) and [RELEASING.md](RELEASING.md). Complete those steps and include the verified catalog in the released APK without requesting separate image approval. Producer-source changes alone do not deliver guest features, and existing installed environments remain preserved.

Before a publishing dispatch, create the immutable `avf-*` tag at the reviewed producer commit using the authorized publisher credentials. Preserve any existing tag and require an exact commit match. The workflow verifies that ref before building and uses `gh release create --verify-tag`; it never asks `GITHUB_TOKEN` to create a workflow-containing ref, which can be denied without workflow scope. A reused draft keeps its original producer tag. The workflow's optional `notify` input defaults to false; Discord notification requires a separate explicit opt-in. For image-only publication, prepare and promote commits with `[skip ci]` so the push cannot trigger the separate automatic app-channel publication workflow, then manually dispatch only `avf_image.yml`. The workflow's `publish` option is false by default and is available only through manual dispatch. Publication waits for both image verification and the catalog-bearing APK build. It requires an immutable release ID, previously committed Korean notes at `docs/release-notes/<release-id>.md`, and the publishing job’s repository `GITHUB_TOKEN` with `Contents: write` in public `nolbo/TERM`. The source commit must belong to shared `dev` history and is recorded in the artifact provenance. For an authorized publication, the factory stages its verified files in an unpublished TERM release draft. The APK job downloads only the factory metadata from that draft and validates its source commit and producer-supplied manifest hash. After the APK gate passes, the publication job downloads and re-verifies the full draft assets, publishes those same bytes, and verifies anonymous public downloads before approving a catalog. This job handoff does not depend on Actions artifact storage; candidate-only runs retain their artifact-based handoff. Debug APK and approved-catalog Actions uploads are optional, and their storage quota failures do not skip or block the required publication checks. Duplicate image release IDs fail without overwriting existing assets. Source and image publication now share TERM; the default branch is `main`, and image production runs from `dev` (select `dev` when dispatching the workflow). Release approval/versioning and patch-note policy remain governed by `AGENTS.md` and `VERSIONING.md`.

An already verified candidate can be promoted without rebuilding it: download that successful run's image artifact, check its recorded source commit against shared `dev`, verify that it includes all image changes required by the app release, verify the full directory against the producer job's manifest hash, and publish those exact bytes with committed Korean notes. An unpublished verified draft can resume through `avf_image.yml` with `reuse_candidate=true`, its exact `release_id` and the original 40-character `candidate_commit`. The workflow requires that producer commit to be an ancestor of the dispatch commit, verifies all existing bytes and requires both normal-boot RPC records plus private-share I/O and owner-loss evidence. It does not rebuild or replace those assets. The review APK job needs `contents: write` to read the draft; default read permission cannot access unpublished releases. Publication still requires authorization and a new immutable release ID; an app release or prerelease deployment request supplies that authorization for required supporting images. Perform the same credential-free public verification below before approving the default catalog. This preserves the reviewed image and its hashes instead of creating different filesystem bytes in another factory run.

Reuse intentionally skips the ancestor kernel job. The APK and publication jobs
use an explicit cancellation check and require their direct verification gates
to succeed, avoiding GitHub Actions' implicit `success()` skip propagation.
A successful image job with skipped APK/publication jobs is not publication.

Runtime downloads must be available without credentials. The unified source and release repository is public; the workflow retains the existing TERM release URLs. HTTPS redirects to the GitHub asset CDN are supported, including signed query strings; content remains pinned by the manifest and artifact SHA-256 values. Host all five `<artifact>.gz` files beside the uncompressed `manifest.properties` at the catalog's URI. Never host mutable `latest` contents under an approved manifest hash. An OEM may use its own immutable HTTPS directory, but the repository's publication verifier intentionally accepts only the TERM repository.

After uploading assets, the publication job downloads the public manifest and streams all five images without authentication. It enforces download/expansion limits and compares the manifest and decompressed artifact hashes before producing the `avf-approved-default-<release-id>` artifact. Missing or damaged public assets fail approval and preserve any existing default pin. The workflow does not push source changes or automatically promote a catalog into app releases.

After validating the pinned manifest, public artifact verification uses at most
two concurrent downloads, each with an independent HTTPS opener. Every stream
still receives full download/expansion limit and decompressed SHA-256 checks;
all workers must succeed before the atomic catalog update. This overlaps transfer
and verification without dropping the full anonymous-download gate.

After review, repeat the public-download verification against the producer artifact and atomically write the default pin:

```sh
python3 image/scripts/verify-avf-release.py \
  --directory image/build/approved --metadata-only \
  --expected-commit <producer-source-commit> \
  --expected-manifest-sha <producer-job-manifest-sha256> \
  --approve image/catalog-approved.properties
```

Commit that verified `image/catalog-approved.properties` on `dev`. Ordinary debug/release builds and the existing Android CI then embed it without extra build properties. Do not commit example hashes or a candidate whose assets have not been published. Source pushes and publication still require the authorization specified in `AGENTS.md`.

For an isolated candidate build, an explicit factory catalog overrides the reviewed default:

```sh
./gradlew :android-app:assembleDebug \
  -PavfImageCatalog=image/build/approved/catalog.properties
```

`image:processResources` validates and embeds the catalog in the APK's Java resources, protected by the APK/product signature. A subsequent build without the property restores the reviewed default, or an empty catalog if no default exists; an earlier override never leaks into later APKs. Users never enter an image URL or checksum. Both ordinary APK variants already package the driver and retain `TerminalApplication`; compatible platform APIs and actual Android permission grants are still required as described in `DEPLOYMENT.md`.

Before publishing a usable product, install its signed APK on an AVF-capable privileged target and run `tests/integration/acceptance.sh`. The source build, a QEMU smoke pass or a stock redroid instance does not establish actual AVF support.

The local image directory picker remains available by design. It is useful for
offline and development installations, but it performs the same manifest and
checksum validation before a VM can be started.

Legacy `androidStorageVersion=1` manifests remain parseable for installed-image compatibility, but the current app never exports Android storage. Future component updates/full transitions remove retired TERM sharing units and drop-ins from the staged filesystem without deleting user folders or distro FUSE support.

## Multiple distributions

The factory accepts `--distribution debian|ubuntu|archlinux`; workflow dispatch exposes the same choice. The default remains Debian for compatibility. Ubuntu/Arch userspace starts from the same checksum-pinned archives as Userspace Linux, prepared in the isolated build container with native systemd/network/package tools. The boot layer is independent of the rootfs; all new Debian, Ubuntu and Arch production uses the verified container-capable kernel and matching module bundle. Both offline QEMU boots require the distribution’s real sudo and package install/remove tests plus failed/successful/idempotent initialization evidence. See [AVF_DISTRIBUTIONS.md](AVF_DISTRIBUTIONS.md).

Each release retains the five-disk artifact contract and pins its own `distribution`. New manifests declare `packageInitializationVersion=1`; older Debian `aptInitializationVersion=1` remains supported. Approval merges the verified pin into a format-v2 distribution catalog without removing other distributions. `merge-avf-catalog.py` also merges the candidate with the committed default before APK verification; `--apk-catalog` verifies the exact merged catalog bytes. The legacy resource filename inside the signed APK remains unchanged for compatibility. New Ubuntu/Arch pins must pass the same producer, anonymous public download and APK checks before promotion. No Ubuntu/Arch public release is implied by source support alone.


## App-private shared-directory broker

The 20261020-r1 images were produced from the reviewed broker source (`f9ea542` for Ubuntu/Arch and `1de0a4b` for Debian), before the later maintenance/update implementation. They declare `shareBrokerVersion=1` and omit `maintenanceVersion`; release APKs keep maintenance actions disabled for these pins. Debug APKs approve catalog entries at the UI boundary, but the service still rejects images without the actual maintenance capability. Publishing these images does not claim or deliver that separate guest-update/migration feature.

The sharing markers in the older approved images describe their historic capability; the current host does not activate it. New images retain `sessionResumeVersion=1` and generic replay transport for terminal recovery while omitting SSHFS/broker services and dependencies. Metadata verification retains legacy marker support. Producing and publishing a new approved image is required before these guest-service removals reach installed environments through an explicit component update or full transition.

## Guest maintenance rollout

The Debian 20261020-r5, Ubuntu 20261020-r4 and Arch 20261020-r3 images include `maintenanceVersion=1` in both their pinned manifests and approved catalog entries. Each sealed system contains its distribution-native `term-guest` package, managed-file inventory, factory package baseline, migration script and fixed maintenance/health commands. Both disposable offline boots install the package and require `TERM_AVF_GUEST_PACKAGE_OK`; the separate normal-systemd boots retain terminal RPC and session-recovery checks. See [AVF_UPDATES.md](AVF_UPDATES.md) for component updates, full transitions and matching-state rollback.

These releases retain the r1 kernel, allowing a supporting APK to offer component updates to those installations. Only the verified APK catalog defines the available target. Image publication and catalog promotion do not change existing APKs or installed environments; users explicitly check and apply an update from a supporting build. Android AVF maintenance boots, owner-death recovery and rollback still require a privileged device and are not established by the Linux factory gates.

## Reusing an unchanged verified kernel

An image dispatch may supply `kernel_run_id` for a successful same-repository dev run of `avf_image.yml`. The workflow requires the producer to be an ancestor in shared dev history and requires identical `image/kernel`, `build-avf-kernel.py` and `avf_kernel.py` inputs. It downloads only that run's commit-named kernel artifact. The existing factory still verifies bundle hashes, the extracted kernel configuration and pinned kernel contract before assembling fresh distribution images. This reuses only the kernel; all rootfs production, both offline boots, both normal-systemd boots, APK verification and public-download gates remain required. It does not permit reusing old root images for a changed filesystem contract.

Kernel reuse must match the intended installed/default kernel hash when preserving component updates. Parallel runs at the same source commit can produce different binaries despite an identical recipe. Check the selected artifact’s actual vmlinuz hash against the pinned current manifest before dispatch. The ext4-20261022-r2 defaults use producer run `39715151260` (`fa4f0fc22c316d24a417e5620e915c626ab246ef5c190b557d38c136955357ee`); the other two runs at that same source commit have different hashes.
