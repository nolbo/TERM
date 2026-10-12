# Debian ARM64 image release procedure

The imported 0.1.0 stable source on `main` does not embed an image URL.
The newer `dev` source includes the verified approved catalog and image producer.
The discoverable AVF workflow is present on default `main`, but its image jobs
run only when dispatched on `dev`; select that branch explicitly. Follow the
[current image procedure on dev](https://github.com/nolbo/TERM/blob/dev/docs/IMAGE_RELEASE.md)
for new image production and publication. The instructions below describe the
original stable image contract. A Debian
cloud image, ISO, or arbitrary link is not interchangeable with this runtime:
it must contain the AVF-compatible kernel, initrd, `system.img`, `home.img`,
and `config.img` contract and the three guest systemd services.

To make **automatic setup** operational in a product/OEM build:

1. Build the ARM64 guest and install `droid-sessiond`, `droid-agent`, and
   `droid-port-agent` with `image/scripts/install-droid-guest.sh`.
2. Produce these six HTTPS-served files in one immutable release directory:
   `manifest.properties`, `vmlinuz`, `initrd.img`, `system.img`, `home.img`,
   and `config.img`.
3. Generate SHA-256 values for all artifacts and put them in
   `manifest.properties`; verify with `DebianImageVerifier` before publishing.
4. Add the release URI, manifest SHA-256, and maximum artifact size to the
   product implementation of `BundledDebianImageCatalog`. The catalog is
   compiled and signed with the APK/OEM image; it must never be populated from
   text entered in the UI or an unsigned remote response.
5. Install the signed APK on an AVF-capable device and run
   `tests/integration/acceptance.sh`.

The local image directory picker remains available by design. It is useful for
offline and development installations, but it performs the same manifest and
checksum validation before a VM can be started.
