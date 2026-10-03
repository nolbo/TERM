# Debian ARM64 image release procedure

End users do not build or repackage Debian images. On a supported privileged product build, creating the first AVF session downloads the signed-build catalog's approved release, decompresses and verifies it, installs it once, and continues startup. Ordinary debug/release builds include the reviewed default catalog when it exists. Until a real image has been published and approved, they retain an empty catalog; a normal APK cannot gain custom VM privileges through this installer.

## Automated producer

The repository's `Build approved AVF Debian image` workflow automatically prepares a candidate when image, guest, or related verification files are pushed to `dev`. It can also be dispatched on `dev` with an explicit immutable release ID. An ARM64 runner builds the guest binaries, downloads Google's official AVF Debian archive, rejects unsafe archive entries, installs the TERM services and CLI, creates the three disks, rebuilds the matching initrd and creates the manifest and pinned catalog. It records the upstream archive SHA-256 and repository commit in `provenance.json`. An optional reviewed source SHA-256 rejects upstream changes. Unsupported archive formats or missing overlay kernel support fail instead of producing an apparently usable image.

The dependent APK job receives the factory metadata, verifies its source commit and the manifest hash supplied by the producer job, and builds a debug APK containing that exact catalog. It checks the embedded catalog, AVF driver, and Rootless payload before uploading the candidate APK. This review APK reuses the canonical Android workflow's version-code sequence; it is not a separate app channel release. Candidate production alone does not publish image assets or make their URLs usable.

Filesystem parsing and guest configuration run in a libguestfs appliance inside an unprivileged container with no network or writable source checkout. The producer installs its dependencies in the container; it does not install host system packages. Production requires Docker, an ARM64 host (or configured ARM64 emulation), and at least 24 GiB of free space on the output filesystem plus sufficient Docker storage. The workflow runs two QEMU boots and checks that the overlay root starts, the guest binaries execute, the config disk mounts read-only, and a file written on the first boot survives the second. This is an image boot gate, not privileged AVF/device/vsock validation.

The appliance uses the direct backend with `LIBGUESTFS_BACKEND_SETTINGS=force_tcg`, so hosted ARM64 runners do not require KVM. Without this setting, libguestfs can detect host KVM support while the container has no KVM device and select an unusable host GIC configuration. Debug/trace output is enabled to report appliance failures. Guest build failures terminate the factory immediately.

For local product development, the same pipeline is one command:

```sh
python3 image/scripts/build-avf-image.py \
  --release-id avf-debian-1 \
  --base-uri https://github.com/nolbo/TERM/releases/download/avf-debian-1/ \
  --output image/build/approved
```

Use an immutable release ID consistently in the URI and command. This is a producer command, not an end-user setup instruction. The producer refuses to overwrite an existing release directory. The six original files remain available for optional offline installation; the workflow uploads gzip-compressed boot/disk files, `manifest.properties`, `catalog.properties`, `provenance.json` and `SHA256SUMS` as its artifact. The writable `home.img` contains the persistent overlay state, including home files and package changes. The initial filesystem is reset after the two-boot test, before checksums are generated.

## Publication and product build

The workflow's `publish` option is false by default and is available only through manual dispatch. Publication waits for both image verification and the catalog-bearing APK build. It requires an immutable release ID, previously committed Korean notes at `docs/release-notes/<release-id>.md`, and `TERMINAL_UPDATES_TOKEN` with release access to public `nolbo/TERM`. The source commit must belong to shared `dev` history and is recorded in the artifact provenance. TERM is a distribution repository: the workflow creates a new image prerelease there without copying private source history or replacing an existing release. Release approval/versioning and patch-note policy remain governed by `AGENTS.md` and `VERSIONING.md`.

Runtime downloads must be available without credentials. The source repository is private, so its release URLs cannot serve automatic installation; the workflow uses public TERM release URLs. HTTPS redirects to the GitHub asset CDN are supported, including signed query strings; content remains pinned by the manifest and artifact SHA-256 values. Host all five `<artifact>.gz` files beside the uncompressed `manifest.properties` at the catalog's URI. Never host mutable `latest` contents under an approved manifest hash. An OEM may use its own immutable HTTPS directory, but the repository's publication verifier intentionally accepts only the TERM distribution repository.

After uploading assets, the publication job downloads the public manifest and streams all five images without authentication. It enforces download/expansion limits and compares the manifest and decompressed artifact hashes before producing the `avf-approved-default-<release-id>` artifact. Missing or damaged public assets fail approval and preserve any existing default pin. The workflow does not push source changes or automatically promote a catalog into app releases.

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
