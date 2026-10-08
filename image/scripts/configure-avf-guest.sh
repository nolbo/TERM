#!/bin/bash
set -euo pipefail
trap 'printf "AVF guest configuration failed at line %s: %s\n" "$LINENO" "$BASH_COMMAND" >&2' ERR
export PATH=/usr/sbin:/usr/bin:/sbin:/bin
test "$(dpkg --print-architecture)" = arm64
command -v unmkinitramfs mkinitramfs depmod modprobe >/dev/null
test -x /usr/bin/systemctl
# Google's direct-boot rootfs has no installed kernel or modules. The paired
# initrd is a modules archive, not an initramfs-tools boot ramdisk.
ramdisk=$(mktemp -d /tmp/term-source-initrd.XXXXXX)
trap 'rm -rf "$ramdisk"' EXIT
unmkinitramfs /tmp/term-avf/source-initrd.img "$ramdisk"
kernel=
for candidate in "$ramdisk"/lib/modules/* "$ramdisk"/usr/lib/modules/* \
                 "$ramdisk"/main/lib/modules/* "$ramdisk"/main/usr/lib/modules/*; do
  [ -d "$candidate" ] || continue
  if [ -n "$kernel" ]; then
    echo 'Source initrd contains more than one kernel module tree' >&2
    exit 1
  fi
  test -f "$candidate/modules.dep"
  test -f "$candidate/modules.builtin"
  version=${candidate##*/}
  if [ -f "/boot/vmlinuz-$version" ]; then
    cmp /tmp/term-avf/source-vmlinuz "/boot/vmlinuz-$version"
  fi
  mkdir -p /lib/modules
  cp -a "$candidate" /lib/modules/
  kernel=$version
done
test -n "$kernel"
depmod -a "$kernel"
modprobe --set-version "$kernel" --show-depends overlay >/dev/null
rm -rf "$ramdisk"
bash /tmp/term-avf/install-droid-guest.sh / /tmp/term-avf/bin
install -m 0755 /tmp/term-avf/image-selftest /usr/local/lib/droid/image-selftest
install -m 0755 /tmp/term-avf/image-user-selftest /usr/local/lib/droid/image-user-selftest
sh /tmp/term-avf/configure-avf-user.sh /tmp/term-avf/droid.sudoers
sh /tmp/term-avf/configure-avf-network.sh / /tmp/term-avf/term-virtio.network
install -m 0755 /tmp/term-avf/initialize-avf-apt.sh /usr/local/lib/droid/initialize-avf-apt.sh
command -v timeout flock >/dev/null
sh /tmp/term-avf/clean-avf-apt.sh /
mkdir -p /etc/droid-config /.droid/lower /.droid/state
chmod 0700 /.droid
mkdir -p /etc/systemd/system/droid-agent.service.d
printf '[Service]\nGroup=droid\n' > /etc/systemd/system/droid-agent.service.d/access.conf
# The root is an overlay with persistent upper/work directories on vdb.
# All package and home writes survive reboot while the distributed base stays RO.
printf 'overlay / overlay defaults 0 0\n/dev/vdc /etc/droid-config ext4 ro,nodev,nosuid,noexec 0 0\n' > /etc/fstab
install -D -m 0755 /tmp/term-avf/droid-overlay /etc/initramfs-tools/scripts/local-bottom/droid-overlay
for module in overlay virtio_blk virtio_pci virtio_mmio ext4; do
  printf '%s\n' "$module" >> /etc/initramfs-tools/modules
done
mkinitramfs -c gzip -o /boot/term-initrd.img "$kernel"
for service in droid-sessiond droid-agent droid-port-agent droid-apt-init; do
  systemctl enable "$service.service"
  systemd-analyze verify "/etc/systemd/system/$service.service"
done
/usr/local/lib/droid/droid-sessiond --self-check
# Preserve only TERM's PTY owner, no browser terminal or passwordless console.
systemctl mask serial-getty@hvc0.service getty@tty1.service ttyd.service
