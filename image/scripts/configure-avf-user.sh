#!/bin/sh
set -eu
# Run only inside the producer's isolated guest, never against an installed VM.
policy="${1:?usage: configure-avf-user.sh SUDOERS_FILE}"
test "$(id -u)" -eq 0
for tool in sudo visudo runuser useradd usermod getent; do
  if ! command -v "$tool" >/dev/null; then
    echo "AVF base image is missing required guest tool: $tool" >&2
    exit 1
  fi
done
if ! id droid >/dev/null 2>&1; then
  useradd --uid 1000 --user-group --create-home --home-dir /home/droid --shell /bin/bash droid
fi
test "$(id -u droid)" -ne 0
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
