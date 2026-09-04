#!/bin/sh
set -eu

# Run inside the prepared Debian ARM64 rootfs before sealing system.img.
root="${1:?usage: install-droid-guest.sh ROOTFS BINDIR}"
bin="${2:?usage: install-droid-guest.sh ROOTFS BINDIR}"

install -d "$root/usr/local/lib/droid" "$root/usr/local/bin" "$root/etc/systemd/system"
install -m 0755 "$bin/droid-sessiond" "$root/usr/local/lib/droid/droid-sessiond"
install -m 0755 "$bin/droid-agent" "$root/usr/local/lib/droid/droid-agent"
install -m 0755 "$bin/droid-port-agent" "$root/usr/local/lib/droid/droid-port-agent"
install -m 0755 "$bin/droid" "$root/usr/local/bin/droid"

for service in droid-sessiond droid-agent droid-port-agent; do
  install -m 0644 "$bin/$service.service" "$root/etc/systemd/system/$service.service"
  mkdir -p "$root/etc/systemd/system/multi-user.target.wants"
  ln -sf "/etc/systemd/system/$service.service" "$root/etc/systemd/system/multi-user.target.wants/$service.service"
done

for shim in "$bin"/termux-*; do
  [ -f "$shim" ] || continue
  install -m 0755 "$shim" "$root/usr/local/bin/$(basename "$shim")"
done
