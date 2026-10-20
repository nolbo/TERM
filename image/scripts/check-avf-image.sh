#!/bin/sh
set -eu
test "$(id -u droid)" -eq 1000
test "$(id -g droid)" -eq 1000
/usr/local/lib/droid/droid-sessiond --self-check
modprobe fuse
command -v sshfs fusermount3 python3 setpriv >/dev/null
mkdir -p /var/lib/droid/health
date +%s > /var/lib/droid/health/complete
sync
