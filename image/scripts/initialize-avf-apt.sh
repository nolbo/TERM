#!/bin/sh
# Fixed guest-only maintenance operation; no caller-supplied command or sources.
set -eu
export PATH=/usr/sbin:/usr/bin:/sbin:/bin
test "$(id -u)" -eq 0
state=/var/lib/droid/apt-initialization
install -d -m 0700 "$state"
exec 9>"$state/lock"
flock -w 30 9
if [ -s "$state/complete" ]; then exit 0; fi
# apt retries DNS/transport failures within a fixed overall deadline. It must
# fail on any repository error rather than claim success using old lists.
timeout --foreground --signal=TERM --kill-after=5s 300s apt-get \
    -o Acquire::PDiffs=false -o APT::Update::Error-Mode=any \
    -o Acquire::Retries=2 -o Acquire::http::Timeout=20 \
    -o Acquire::https::Timeout=20 -o DPkg::Lock::Timeout=30 update
# The host may stop an initialization-only VM immediately after the response.
# Flush indexes before publishing success, then flush the receipt itself.
sync
date +%s > "$state/complete.tmp"
mv -f "$state/complete.tmp" "$state/complete"
sync
