#!/bin/sh
# One fixed guest maintenance operation. No caller-supplied command or repositories.
set -eu
export PATH=/usr/sbin:/usr/bin:/sbin:/bin
test "$(id -u)" -eq 0
distribution=$(cat /etc/droid/distribution)
state=/var/lib/droid/package-initialization
install -d -m 0700 "$state"
exec 9>"$state/lock"
flock -w 30 9
if [ -s "$state/complete" ]; then exit 0; fi
case "$distribution" in
  debian|ubuntu)
    timeout --foreground --signal=TERM --kill-after=5s 300s apt-get \
      -o Acquire::PDiffs=false -o APT::Update::Error-Mode=any \
      -o Acquire::Retries=2 -o Acquire::http::Timeout=20 \
      -o Acquire::https::Timeout=20 -o DPkg::Lock::Timeout=30 update
    ;;
  archlinux)
    # Refresh only: initialization never upgrades an existing user's packages.
    timeout --foreground --signal=TERM --kill-after=5s 300s pacman --sync --refresh --noconfirm
    ;;
  *) echo 'Unsupported AVF distribution' >&2; exit 1;;
esac
sync
date +%s > "$state/complete.tmp"
mv -f "$state/complete.tmp" "$state/complete"
sync
