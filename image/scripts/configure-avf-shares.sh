#!/bin/sh
set -eu
root=${1:?usage: configure-avf-shares.sh ROOTFS SOURCE_DIR}
source=${2:?usage: configure-avf-shares.sh ROOTFS SOURCE_DIR}
install -D -m 0755 "$source/mount-shared-directories.sh" "$root/usr/local/lib/droid/mount-shared-directories.sh"
install -D -m 0644 "$source/term-shares.service" "$root/etc/systemd/system/term-shares.service"
install -d "$root/etc/systemd/system/droid-sessiond.service.d" "$root/etc/systemd/system/multi-user.target.wants"
printf '[Unit]\nRequires=term-shares.service\nAfter=term-shares.service\n' > "$root/etc/systemd/system/droid-sessiond.service.d/shares.conf"
ln -sf /etc/systemd/system/term-shares.service "$root/etc/systemd/system/multi-user.target.wants/term-shares.service"
