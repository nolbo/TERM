#!/bin/bash
# Factory-only: recreate a disposable full root from the sealed system seed.
set -euo pipefail
seed=${1:?system image}
root=${2:?disposable root image}
test -f "$seed" && test ! -L "$seed"
# Preserve the former 8 GiB writable budget in addition to the complete base.
minimum=${3:-$(( $(stat -c %s "$seed") + 8 * 1024 * 1024 * 1024 ))}
case "$minimum" in ''|*[!0-9]*) exit 1;; esac
test "$minimum" -gt 0
test ! -L "$root"
test ! "$seed" -ef "$root"
cp --sparse=always "$seed" "$root"
if [ "$(stat -c %s "$root")" -lt "$minimum" ]; then
  truncate -s "$minimum" "$root"
fi
# A different filesystem identity prevents ambiguity with the attached RO seed.
e2fsck -fp "$root" || [ "$?" -eq 1 ]
tune2fs -U random -L droid-root "$root"
resize2fs "$root"
