#!/bin/sh
set -eu
root=${1:?usage: configure-avf-session.sh ROOTFS SOURCE_DIR}
source=${2:?usage: configure-avf-session.sh ROOTFS SOURCE_DIR}
install -D -m 0755 "$source/private-share-transport.py" "$root/usr/local/lib/droid/private-share-transport.py"
install -D -m 0755 "$source/session-recovery.py" "$root/usr/local/lib/droid/session-recovery.py"
install -D -m 0644 "$source/term-session-recovery.service" "$root/etc/systemd/system/term-session-recovery.service"
install -d "$root/etc/systemd/system/multi-user.target.wants"
ln -sf /etc/systemd/system/term-session-recovery.service "$root/etc/systemd/system/multi-user.target.wants/term-session-recovery.service"
