#!/bin/sh
set -eu

if [ "$#" -ne 1 ]; then
    echo "usage: $0 ROOTFS_DIRECTORY" >&2
    exit 2
fi

script_directory=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
rootfs=$(readlink -f -- "$1")
if [ ! -d "$rootfs" ] || [ "$rootfs" = "/" ]; then
    echo "refusing invalid rootfs directory: $rootfs" >&2
    exit 2
fi

install -D -m 0755 "$script_directory/xtermx_agent.py" \
    "$rootfs/usr/libexec/xtermx-agent"
install -D -m 0755 "$script_directory/xtermx_api.py" \
    "$rootfs/usr/bin/xtermx-api"
install -D -m 0644 "$script_directory/xtermx-agent.service" \
    "$rootfs/etc/systemd/system/xtermx-agent.service"

ln -sfn xtermx-api "$rootfs/usr/bin/termux-clipboard-get"
ln -sfn xtermx-api "$rootfs/usr/bin/termux-clipboard-set"
ln -sfn xtermx-api "$rootfs/usr/bin/termux-open-url"
ln -sfn xtermx-api "$rootfs/usr/bin/termux-share"

mkdir -p "$rootfs/etc/systemd/system/multi-user.target.wants"
ln -sfn ../xtermx-agent.service \
    "$rootfs/etc/systemd/system/multi-user.target.wants/xtermx-agent.service"

echo "installed XTermX guest agent into $rootfs"
