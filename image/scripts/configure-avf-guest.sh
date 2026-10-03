#!/bin/bash
set -euo pipefail
test "$(dpkg --print-architecture)" = arm64
test -x /usr/sbin/update-initramfs
test -x /usr/bin/systemctl
kernel=
for candidate in /lib/modules/*; do
  version=${candidate##*/}
  if cmp -s /tmp/term-avf/source-vmlinuz "/boot/vmlinuz-$version"; then
    kernel=$version
    break
  fi
done
test -n "$kernel"
grep -Eq '^CONFIG_OVERLAY_FS=(y|m)$' "/boot/config-$kernel"
bash /tmp/term-avf/install-droid-guest.sh / /tmp/term-avf/bin
install -m 0755 /tmp/term-avf/image-selftest /usr/local/lib/droid/image-selftest
if ! id droid >/dev/null 2>&1; then
  useradd --create-home --shell /bin/bash droid
fi
mkdir -p /home/user /workspace /etc/droid-config /.droid/lower /.droid/state
chmod 0700 /.droid
chown droid:droid /home/user /workspace
mkdir -p /etc/systemd/system/droid-agent.service.d
printf '[Service]\nGroup=droid\n' > /etc/systemd/system/droid-agent.service.d/access.conf
# The root is an overlay with persistent upper/work directories on vdb.
# All package and home writes survive reboot while the distributed base stays RO.
printf 'overlay / overlay defaults 0 0\n/dev/vdc /etc/droid-config ext4 ro,nodev,nosuid,noexec 0 0\n' > /etc/fstab
install -D -m 0755 /tmp/term-avf/droid-overlay /etc/initramfs-tools/scripts/local-bottom/droid-overlay
for module in overlay virtio_blk virtio_pci virtio_mmio ext4; do
  printf '%s\n' "$module" >> /etc/initramfs-tools/modules
done
update-initramfs -u -k "$kernel"
cp "/boot/initrd.img-$kernel" /boot/term-initrd.img
for service in droid-sessiond droid-agent droid-port-agent; do
  systemctl enable "$service.service"
  systemd-analyze verify "/etc/systemd/system/$service.service"
done
/usr/local/lib/droid/droid-sessiond --self-check
# Preserve only TERM's PTY owner, no browser terminal or passwordless console.
systemctl mask serial-getty@hvc0.service getty@tty1.service ttyd.service
