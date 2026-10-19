#!/bin/bash
set -euo pipefail
# The only networked appliance step: install signed packages into Google's
# Debian rootfs. Final assembly and boot verification remain network-isolated.
cp --sparse=always /input/root_part /output/root_part
if [ "$(stat -c %s /output/root_part)" -lt $((6 * 1024 * 1024 * 1024)) ]; then
  truncate -s 6G /output/root_part
fi
guestfish --rw --format=raw -a /output/root_part <<'EOF'
run
resize2fs /dev/sda
EOF
virt-customize --format raw -a /output/root_part --network \
  --run-command 'test "$(. /etc/os-release; echo "$ID")" = debian' \
  --run-command 'apt-get -o APT::Update::Error-Mode=any update' \
  --run-command 'DEBIAN_FRONTEND=noninteractive apt-get -y --no-install-recommends install python3 sshfs fuse3'
cp /input/vmlinuz /input/initrd.img /output/
