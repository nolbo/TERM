#!/bin/sh
set -eu
# Run only inside the producer's isolated guest, never against an installed VM.
policy="${1:?usage: configure-avf-user.sh SUDOERS_FILE}"
test "$(id -u)" -eq 0
for tool in sudo visudo runuser useradd usermod groupadd groupmod getent; do
  if ! command -v "$tool" >/dev/null; then
    echo "AVF base image is missing required guest tool: $tool" >&2
    exit 1
  fi
done
if ! id droid >/dev/null 2>&1; then
  # Pinned rootfs archives can already have a bootstrap user (e.g. Arch's alarm).
  # Reuse its identity and move its home instead of deleting files or sharing UID 1000.
  user_record=$(getent passwd 1000 || true)
  group_record=$(getent group 1000 || true)
  if ! getent group droid >/dev/null; then
    if [ -n "$group_record" ]; then
      groupmod --new-name droid "${group_record%%:*}"
    else
      groupadd --gid 1000 droid
    fi
  fi
  test "$(getent group droid | cut -d: -f3)" -eq 1000
  if [ -n "$user_record" ]; then
    usermod --login droid --gid droid --move-home --home /home/droid --shell /bin/bash "${user_record%%:*}"
  else
    useradd --uid 1000 --gid droid --create-home --home-dir /home/droid --shell /bin/bash droid
  fi
fi
test "$(id -u droid)" -eq 1000
test "$(id -g droid)" -eq 1000
test "$(getent passwd droid | cut -d: -f6)" = /home/droid
usermod --lock droid
mkdir -p /home/droid /workspace
chown droid:droid /home/droid /workspace
install -d -m 0755 /etc/sudoers.d
install -o 0 -g 0 -m 0440 "$policy" /etc/sudoers.d/90-term-droid
visudo --check
# Reject a missing/disabled setuid executable or an ineffective sudoers include.
test -u "$(command -v sudo)"
root_uid=$(runuser -u droid -- sudo -n id -u)
test "$root_uid" -eq 0
