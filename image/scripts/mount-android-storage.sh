#!/bin/sh
set -eu
# Only the host-authorized primary shared storage receives this fixed export tag.
cmdline=${1:-/proc/cmdline}
destination=${2:-/sdcard}
case " $(cat "$cmdline") " in
  *' term.android-storage=1 '*) ;;
  *) exit 0 ;;
esac
modprobe virtiofs
test ! -L "$destination"
mkdir -p "$destination"
chmod 0755 "$destination"
mount -t virtiofs -o nodev,nosuid,noexec term-storage "$destination"
