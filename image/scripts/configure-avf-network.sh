#!/bin/sh
set -eu
# Configure only the producer's disposable rootfs, never the Android host.
root="${1:?usage: configure-avf-network.sh ROOTFS NETWORK_FILE}"
network="${2:?usage: configure-avf-network.sh ROOTFS NETWORK_FILE}"
test -f "$network"
for service in systemd-networkd systemd-resolved; do
  if [ ! -x "$root/usr/lib/systemd/$service" ]; then
    echo "AVF base image is missing required network service: $service" >&2
    exit 1
  fi
done
# Match the virtual NIC driver rather than a PCI-dependent name such as enp0s8.
install -D -m 0644 "$network" "$root/etc/systemd/network/10-term-virtio.network"
systemctl --root="$root" enable systemd-networkd.service systemd-resolved.service
ln -sfn /run/systemd/resolve/stub-resolv.conf "$root/etc/resolv.conf"
