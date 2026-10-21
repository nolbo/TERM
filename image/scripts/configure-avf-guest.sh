#!/bin/bash
set -euo pipefail
trap 'printf "AVF guest configuration failed at line %s: %s\n" "$LINENO" "$BASH_COMMAND" >&2' ERR
export PATH=/usr/sbin:/usr/bin:/sbin:/bin
# PID 1 needs udev to activate .device units such as the config disk in fstab.
# A devtmpfs node alone does not make that disk available to systemd mounts.
test -x /usr/lib/systemd/systemd-udevd
distribution=${1:-debian}
. /etc/os-release
case "$distribution:$ID" in
  debian:debian|ubuntu:ubuntu|archlinux:arch|archlinux:archarm) ;;
  *) echo 'Rootfs distribution mismatch' >&2; exit 1;;
esac
case "$distribution" in
  debian|ubuntu) test "$(dpkg --print-architecture)" = arm64; command -v apt-get dpkg-deb >/dev/null;;
  archlinux) test "$(uname -m)" = aarch64; command -v pacman repo-add bsdtar >/dev/null;;
  *) exit 1;;
esac
install -d -m 0755 /etc/droid
printf '%s\n' "$distribution" > /etc/droid/distribution
bash /tmp/term-avf/install-droid-guest.sh / /tmp/term-avf/bin
install -m 0755 /tmp/term-avf/image-selftest /usr/local/lib/droid/image-selftest
install -m 0755 /tmp/term-avf/image-container-selftest /usr/local/lib/droid/image-container-selftest
for script in image-user-selftest image-apt-selftest image-pacman-selftest image-update-selftest initialize-avf-packages.sh maintain-avf-image.sh migrate-avf-state.py check-avf-image.sh; do
  install -m 0755 "/tmp/term-avf/$script" "/usr/local/lib/droid/$script"
done
sh /tmp/term-avf/configure-avf-user.sh /tmp/term-avf/droid.sudoers
test "$(id -u droid)" -eq 1000
# Dependencies are provisioned before this offline assembly phase.
command -v sshfs fusermount3 python3 setpriv >/dev/null
for tool in ip iptables-legacy ip6tables-legacy iptables-nft ip6tables-nft; do
  command -v "$tool" >/dev/null
done
sh /tmp/term-avf/configure-avf-shares.sh / /tmp/term-avf
sh /tmp/term-avf/configure-avf-network.sh / /tmp/term-avf/term-virtio.network
command -v timeout flock >/dev/null
case "$distribution" in
  debian|ubuntu) sh /tmp/term-avf/clean-avf-apt.sh /;;
  archlinux)
    # Preserve installed-package state, trust keys and repository configuration.
    rm -f /var/lib/pacman/sync/* /var/cache/pacman/pkg/*
    ;;
esac
mkdir -p /etc/droid-config /.droid/lower /.droid/state
chmod 0700 /.droid
mkdir -p /etc/systemd/system/droid-agent.service.d
printf '[Service]\nGroup=droid\n' > /etc/systemd/system/droid-agent.service.d/access.conf
# The root is an overlay with persistent upper/work directories on vdb.
# All package and home writes survive reboot while the distributed base stays RO.
printf 'overlay / overlay defaults 0 0\n/dev/vdc /etc/droid-config ext4 ro,nodev,nosuid,noexec 0 0\n' > /etc/fstab
for service in droid-sessiond droid-agent droid-port-agent droid-apt-init droid-file-transfer term-shares term-share-broker term-android-storage; do
  systemctl enable "$service.service"
  systemd-analyze verify "/etc/systemd/system/$service.service"
done
/usr/local/lib/droid/droid-sessiond --self-check
python3 /tmp/term-avf/build-guest-package.py / "$distribution"
# Preserve only TERM's PTY owner, no browser terminal or passwordless console.
systemctl mask serial-getty@hvc0.service getty@tty1.service ttyd.service
