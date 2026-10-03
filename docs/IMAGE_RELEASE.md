# Debian ARM64 image release procedure

End users do not build or repackage Debian images. On a supported privileged product build, creating the first AVF session downloads the signed-build catalog's approved release, decompresses and verifies it, installs it once, and continues startup. The public build has no release URL until the product build provides a catalog; a normal APK cannot gain custom VM privileges through this installer.

## Automated producer

The repository's `Build approved AVF Debian image` workflow runs on `dev` using an ARM64 runner. It builds the guest binaries, downloads Google's official AVF Debian archive, rejects unsafe archive entries, installs the TERM services and CLI, creates the three disks, rebuilds the matching initrd and creates the manifest and pinned catalog. It records the upstream archive SHA-256 and repository commit in `provenance.json`. An optional reviewed source SHA-256 rejects upstream changes. Unsupported archive formats or missing overlay kernel support fail instead of producing an apparently usable image.

Filesystem parsing and guest configuration run in a libguestfs appliance inside an unprivileged container with no network or writable source checkout. The producer installs its dependencies in the container; it does not install host system packages. Production requires Docker, an ARM64 host (or configured ARM64 emulation), and at least 24 GiB of free space on the output filesystem plus sufficient Docker storage. The workflow runs two QEMU boots and checks that the overlay root starts, the guest binaries execute, the config disk mounts read-only, and a file written on the first boot survives the second. This is an image boot gate, not privileged AVF/device/vsock validation.

For local product development, the same pipeline is one command:

```sh
python3 image/scripts/build-avf-image.py \
  --release-id avf-debian-1 \
  --base-uri https://example.com/avf/avf-debian-1/ \
  --output image/build/approved
```

Replace the example URI with the product's immutable HTTPS release directory. This is a producer command, not an end-user setup instruction. The producer refuses to overwrite an existing release directory. The six original files remain available for optional offline installation; the workflow uploads gzip-compressed boot/disk files, `manifest.properties`, `catalog.properties`, `provenance.json` and `SHA256SUMS` as its artifact. The writable `home.img` contains the persistent overlay state, including home files and package changes. The initial filesystem is reset after the two-boot test, before checksums are generated.

## Publication and product build

The workflow's `publish` option is false by default. Explicit publication requires an immutable release ID and previously committed Korean notes at `docs/release-notes/<release-id>.md`. Publication creates a new GitHub release at the selected commit from shared `dev` history and uploads assets; it never replaces an existing release. Without publication, the artifact is for review and testing only and its catalog URL is not yet downloadable. Release approval/versioning and patch-note policy remain governed by `AGENTS.md` and `VERSIONING.md`.

An OEM may serve the same assets from its own HTTPS release directory. For GitHub assets, HTTPS redirects to the asset CDN are supported; content remains pinned by the manifest and artifact SHA-256 values. Host all five `<artifact>.gz` files beside the uncompressed `manifest.properties` at the catalog's URI. Never host mutable `latest` contents under an approved manifest hash.

The product build consumes the factory's catalog with:

```sh
./gradlew :android-app:assembleDebug \
  -PavfImageCatalog=image/build/approved/catalog.properties
```

`image:processResources` validates and embeds the catalog in the APK's Java resources, protected by the APK/product signature. Incremental builds without the property overwrite it with an empty catalog, preventing a previous product build from leaking its catalog into a public APK. Users never enter an image URL or checksum. Product builds must also compile the matching system-SDK driver and select `AospRuntimeApplication` as described in `DEPLOYMENT.md`; the factory does not grant Android privileges.

Before publishing a usable product, install its signed APK on an AVF-capable privileged target and run `tests/integration/acceptance.sh`. The source build, a QEMU smoke pass or a stock redroid instance does not establish actual AVF support.

The local image directory picker remains available by design. It is useful for
offline and development installations, but it performs the same manifest and
checksum validation before a VM can be started.
