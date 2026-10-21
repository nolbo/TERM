#!/bin/bash
set -euo pipefail
test "$(uname -m)" = aarch64
cp --sparse=always /input/root_part /output/system.img
if [ "$(stat -c %s /output/system.img)" -lt $((6 * 1024 * 1024 * 1024)) ]; then
  truncate -s 6G /output/system.img
fi
cp /input/vmlinuz /payload/source-vmlinuz
cp /input/initrd.img /payload/source-initrd.img
if [ -f /input/kernel.config ]; then
  cp /input/kernel.config /input/kernel-build.json /payload/
fi
# libguestfs isolates filesystem parsing and guest commands in an appliance VM.
# No host loop mount, privileged container, network or source tree write access.
tar -cf /tmp/payload.tar -C /payload .
guestfish --rw --format=raw -a /output/system.img <<'EOF'
run
resize2fs /dev/sda
mount /dev/sda /
mkdir /tmp/term-avf
tar-in /tmp/payload.tar /tmp/term-avf
EOF
virt-customize --format raw -a /output/system.img --no-network \
  --run-command 'bash /tmp/term-avf/configure-avf-boot.sh'
guestfish --ro --format=raw -a /output/system.img -m /dev/sda <<'EOF_BOOT'
download /boot/term-initrd.img /output/initrd.img
tar-out /lib/modules /tmp/kernel-modules.tar
EOF_BOOT
if [ "${AVF_DISTRIBUTION:-debian}" != debian ]; then
  # Rootfs and boot layer are separate inputs; no Debian dpkg state is copied.
  rm /output/system.img
  truncate -s 6G /output/system.img
  mkfs.ext4 -F -L droid-system -O '^metadata_csum_seed,^orphan_file' /output/system.img
  guestfish --rw --format=raw -a /output/system.img -m /dev/sda <<'EOF_ROOT'
tar-in /prepared/rootfs.tar /
mkdir-p /lib/modules
tar-in /tmp/kernel-modules.tar /lib/modules
mkdir-p /tmp/term-avf
tar-in /tmp/payload.tar /tmp/term-avf
EOF_ROOT
fi
if [ -f /input/kernel.config ]; then
  # Keep the final config and build identity in the immutable system image.
  guestfish --rw --format=raw -a /output/system.img -m /dev/sda <<'EOF_KERNEL'
mkdir-p /usr/share/term
upload /input/kernel.config /usr/share/term/kernel.config
upload /input/kernel-build.json /usr/share/term/kernel-build.json
EOF_KERNEL
fi
virt-customize --format raw -a /output/system.img --no-network \
  --run-command "bash /tmp/term-avf/configure-avf-guest.sh ${AVF_DISTRIBUTION:-debian}" \
  --run-command 'rm -rf /tmp/term-avf'
# Appliance resolver handling must not leave builder DNS in a sealed image.
guestfish --rw --format=raw -a /output/system.img -m /dev/sda <<'EOF_RESOLVER'
rm-f /etc/resolv.conf
ln-s /run/systemd/resolve/stub-resolv.conf /etc/resolv.conf
EOF_RESOLVER
cp /input/vmlinuz /output/vmlinuz
if [ -f /input/kernel-build.json ]; then
  # Factory-only marker makes the two-boot probe require its runtime result.
  cp /input/kernel-build.json /output/kernel-build.json
fi
truncate -s 8G /output/home.img
mkfs.ext4 -F -L droid-state -O '^metadata_csum_seed,^orphan_file' /output/home.img
mkdir -p /tmp/config
printf 'formatVersion=1\nstateDevice=/dev/vdb\n' > /tmp/config/runtime.properties
truncate -s 16M /output/config.img
mkfs.ext4 -F -L droid-config -O '^metadata_csum_seed,^orphan_file' -d /tmp/config /output/config.img
python3 /usr/local/bin/smoke-avf-image.py
# Reset only the factory's scratch state after proving persistence on two boots.
guestfish --rw --format=raw -a /output/home.img -m /dev/sda <<'EOF'
rm-rf /upper
rm-rf /work
EOF
e2fsck -fn /output/system.img
e2fsck -fn /output/home.img
e2fsck -fn /output/config.img
