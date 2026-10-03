# Privileged AOSP deployment

Run the automated image producer or its ARM64 workflow described in `IMAGE_RELEASE.md`. It builds the Rust guest, installs and enables `droid-sessiond`, `droid-agent`, and `droid-port-agent`, prepares the persistent overlay image, and generates the manifest and approved catalog. End users do not prepare a rootfs or checksum manifest.

The product manifest selects `AospRuntimeApplication`, is platform/OEM signed,
and receives the AVF permissions described in `platform/aosp-avf-runtime`. Include the generated `catalog.properties` using `-PavfImageCatalog=...` so the first AVF session installs automatically. Both application variants implement WorkManager's on-demand main-process configuration; retain the manifest removal of `WorkManagerInitializer` and include `work-multiprocess` when compiling the product driver.
Run `tests/integration/acceptance.sh` on the target device for the end-to-end
acceptance sequence.
