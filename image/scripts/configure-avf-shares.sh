#!/bin/sh
set -eu
root=${1:?usage: configure-avf-shares.sh ROOTFS SOURCE_DIR}
source=${2:?usage: configure-avf-shares.sh ROOTFS SOURCE_DIR}
install -D -m 0755 "$source/mount-shared-directories.sh" "$root/usr/local/lib/droid/mount-shared-directories.sh"
install -D -m 0644 "$source/term-shares.service" "$root/etc/systemd/system/term-shares.service"
install -D -m 0755 "$source/private-share-broker.py" "$root/usr/local/lib/droid/private-share-broker.py"
install -D -m 0644 "$source/term-share-broker.service" "$root/etc/systemd/system/term-share-broker.service"
install -d "$root/etc/systemd/system/multi-user.target.wants"
ln -sf /etc/systemd/system/term-share-broker.service "$root/etc/systemd/system/multi-user.target.wants/term-share-broker.service"
# The unprivileged sshfs client uses the distro's narrow fusermount3 helper.
touch "$root/etc/fuse.conf"
grep -qx 'user_allow_other' "$root/etc/fuse.conf" || printf '\nuser_allow_other\n' >> "$root/etc/fuse.conf"
install -D -m 0755 "$source/mount-android-storage.sh" "$root/usr/local/lib/droid/mount-android-storage.sh"
install -D -m 0644 "$source/term-android-storage.service" "$root/etc/systemd/system/term-android-storage.service"
install -d "$root/etc/systemd/system/droid-sessiond.service.d" "$root/etc/systemd/system/multi-user.target.wants"
printf '[Unit]\nRequires=term-shares.service\nAfter=term-shares.service\n' > "$root/etc/systemd/system/droid-sessiond.service.d/shares.conf"
ln -sf /etc/systemd/system/term-shares.service "$root/etc/systemd/system/multi-user.target.wants/term-shares.service"
printf '[Unit]\nRequires=term-android-storage.service\nAfter=term-android-storage.service\n' > "$root/etc/systemd/system/droid-sessiond.service.d/android-storage.conf"
ln -sf /etc/systemd/system/term-android-storage.service "$root/etc/systemd/system/multi-user.target.wants/term-android-storage.service"
