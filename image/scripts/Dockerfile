FROM rust:1.89.0-bookworm AS guest
WORKDIR /src
COPY guest /src/guest
RUN mkdir /binaries && \
    for package in droid-sessiond droid-agent droid-port-agent droid-cli; do \
      cargo build --release --manifest-path guest/$package/Cargo.toml; \
    done && \
    cp guest/droid-sessiond/target/release/droid-sessiond \
       guest/droid-agent/target/release/droid-agent \
       guest/droid-port-agent/target/release/droid-port-agent \
       guest/droid-cli/target/release/droid /binaries/ && \
    cp guest/droid-sessiond/droid-sessiond.service guest/droid-agent/droid-agent.service \
       guest/droid-port-agent/droid-port-agent.service guest/termux-api-compat/termux-* /binaries/

FROM debian:bookworm-slim
RUN apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
      libguestfs-tools linux-image-arm64 qemu-system-arm e2fsprogs python3 ca-certificates && \
    rm -rf /var/lib/apt/lists/*
COPY --from=guest /binaries /payload/bin
COPY image/scripts/install-droid-guest.sh /payload/install-droid-guest.sh
COPY image/scripts/configure-avf-guest.sh /payload/configure-avf-guest.sh
COPY image/scripts/droid-overlay /payload/droid-overlay
COPY image/scripts/image-selftest /payload/image-selftest
COPY image/scripts/smoke-avf-image.py /usr/local/bin/smoke-avf-image.py
COPY image/scripts/assemble-avf-image.sh /usr/local/bin/assemble-avf-image
ENV LIBGUESTFS_BACKEND=direct
ENTRYPOINT ["bash", "/usr/local/bin/assemble-avf-image"]
