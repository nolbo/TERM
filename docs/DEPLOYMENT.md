# Privileged AOSP deployment

Build the guest Rust binaries for Debian ARM64, then run
`image/scripts/install-droid-guest.sh ROOTFS BINDIR` before generating the
verified image manifest. The script installs and enables `droid-sessiond`,
`droid-agent`, and `droid-port-agent` under systemd.

The product manifest selects `AospRuntimeApplication`, is platform/OEM signed,
and receives the AVF permissions described in `platform/aosp-avf-runtime`.
Run `tests/integration/acceptance.sh` on the target device for the end-to-end
acceptance sequence.
