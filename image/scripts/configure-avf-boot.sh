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
if [ -f /tmp/term-avf/kernel-build.json ]; then
  expected=$(python3 -c 'import json; print(json.load(open("/tmp/term-avf/kernel-build.json"))["kernelRelease"])')
  test "$kernel" = "$expected" || { echo 'Kernel/module release mismatch' >&2; exit 1; }
fi
depmod -a "$kernel"
modprobe --set-version "$kernel" --show-depends overlay >/dev/null
modprobe --set-version "$kernel" --show-depends virtiofs >/dev/null
rm -rf "$ramdisk"
install -D -m 0755 /tmp/term-avf/droid-overlay /etc/initramfs-tools/scripts/local-bottom/droid-overlay
for module in overlay virtio_blk virtio_pci virtio_mmio ext4 virtiofs; do
  printf '%s\n' "$module" >> /etc/initramfs-tools/modules
done
mkinitramfs -c gzip -o /boot/term-initrd.img "$kernel"
