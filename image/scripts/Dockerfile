FROM rust:1.89.0-bookworm AS guest
WORKDIR /src
COPY guest /src/guest
RUN set -eu; mkdir /binaries && \
    for package in droid-sessiond droid-agent droid-port-agent droid-cli; do \
      cargo build --release --manifest-path guest/$package/Cargo.toml || exit 1; \
    done && \
    cp guest/droid-sessiond/target/release/droid-sessiond \
       guest/droid-agent/target/release/droid-agent \
       guest/droid-port-agent/target/release/droid-port-agent \
       guest/droid-cli/target/release/droid /binaries/ && \
    cp guest/droid-sessiond/droid-sessiond.service guest/droid-agent/droid-agent.service \
       guest/droid-port-agent/droid-port-agent.service guest/droid-sessiond/droid-apt-init.service \
       guest/droid-agent/droid-file-transfer.service guest/termux-api-compat/termux-* /binaries/

FROM debian:bookworm-slim
ARG AVF_DISTRIBUTION=debian
ENV AVF_DISTRIBUTION=$AVF_DISTRIBUTION
RUN apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
      libguestfs-tools linux-image-arm64 qemu-system-arm e2fsprogs python3 ca-certificates xz-utils && \
    rm -rf /var/lib/apt/lists/*
COPY image/scripts/prepare-avf-rootfs.py image/scripts/avf-distributions.json /usr/local/lib/term-factory/
RUN python3 /usr/local/lib/term-factory/prepare-avf-rootfs.py "$AVF_DISTRIBUTION" /prepared
COPY --from=guest /binaries /payload/bin
COPY image/scripts/install-droid-guest.sh /payload/install-droid-guest.sh
COPY image/scripts/configure-avf-boot.sh /payload/configure-avf-boot.sh
COPY image/scripts/configure-avf-guest.sh /payload/configure-avf-guest.sh
COPY image/scripts/configure-avf-user.sh /payload/configure-avf-user.sh
COPY image/scripts/configure-avf-network.sh /payload/configure-avf-network.sh
COPY image/scripts/configure-avf-shares.sh /payload/configure-avf-shares.sh
COPY image/scripts/mount-shared-directories.sh /payload/mount-shared-directories.sh
COPY image/scripts/term-shares.service /payload/term-shares.service
COPY image/scripts/mount-android-storage.sh /payload/mount-android-storage.sh
COPY image/scripts/term-android-storage.service /payload/term-android-storage.service
COPY image/scripts/initialize-avf-packages.sh /payload/initialize-avf-packages.sh
COPY image/scripts/clean-avf-apt.sh /payload/clean-avf-apt.sh
COPY image/scripts/term-virtio.network /payload/term-virtio.network
COPY image/scripts/droid.sudoers /payload/droid.sudoers
COPY image/scripts/droid-overlay /payload/droid-overlay
COPY image/scripts/image-selftest /payload/image-selftest
COPY image/scripts/image-apt-selftest /payload/image-apt-selftest
COPY image/scripts/image-pacman-selftest /payload/image-pacman-selftest
COPY image/scripts/image-user-selftest /payload/image-user-selftest
COPY image/scripts/smoke-avf-image.py /usr/local/bin/smoke-avf-image.py
COPY image/scripts/assemble-avf-image.sh /usr/local/bin/assemble-avf-image
# Hosted ARM64 runners do not expose KVM. Avoid an unusable host GIC fallback.
ENV LIBGUESTFS_BACKEND=direct LIBGUESTFS_BACKEND_SETTINGS=force_tcg LIBGUESTFS_DEBUG=1 LIBGUESTFS_TRACE=1
ENTRYPOINT ["bash", "/usr/local/bin/assemble-avf-image"]
