#!/bin/bash
set -euo pipefail
test "$(uname -m)" = aarch64
cp --sparse=always /input/root_part /output/system.img
if [ "$(stat -c %s /output/system.img)" -lt $((6 * 1024 * 1024 * 1024)) ]; then
  truncate -s 6G /output/system.img
fi
cp /input/vmlinuz /payload/source-vmlinuz
cp /input/initrd.img /payload/source-initrd.img
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
  --run-command 'bash /tmp/term-avf/configure-avf-guest.sh' \
  --run-command 'rm -rf /tmp/term-avf'
# Seal the runtime link outside guest command customization so appliance
# resolver handling cannot leave builder DNS in the distributed image.
guestfish --rw --format=raw -a /output/system.img -m /dev/sda <<'EOF'
rm-f /etc/resolv.conf
ln-s /run/systemd/resolve/stub-resolv.conf /etc/resolv.conf
download /boot/term-initrd.img /output/initrd.img
EOF
cp /input/vmlinuz /output/vmlinuz
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
