#!/bin/sh
set -eu
root=${1:?usage: configure-avf-session.sh ROOTFS SOURCE_DIR}
# sessiond now serves the host directly. Retire only obsolete TERM components.
rm -f "$root/etc/systemd/system/multi-user.target.wants/term-session-recovery.service" \
  "$root/etc/systemd/system/term-session-recovery.service" \
  "$root/usr/local/lib/droid/session-recovery.py" \
  "$root/usr/local/lib/droid/private-share-transport.py"
