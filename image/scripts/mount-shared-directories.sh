#!/bin/sh
set -eu
# Root-only guest boot operation. The host supplies only a versioned tag/name table.
cmdline=${1:-/proc/cmdline}
index=${2:-/run/term-shares-index}
destination=${3:-/mnt/term-shares}
case " $(cat "$cmdline") " in
  *' term.share-broker=1 '*)
    count=0
    while [ ! -f /run/term-share-broker.ready ]; do
      count=$((count + 1))
      [ "$count" -le 45 ] || exit 1
      sleep 1
    done
    exit 0
    ;;
esac
case " $(cat "$cmdline") " in
  *' term.virtiofs=1 '*) ;;
  *) exit 0 ;;
esac
modprobe virtiofs
test ! -L "$index" && test ! -L "$destination"
mkdir -p "$index" "$destination"
# The service keeps private state at 0700, but droid must traverse the mount parent.
chmod 0755 "$destination"
mount -t virtiofs -o ro,nodev,nosuid,noexec term-shares "$index"
table=$(mktemp "${TMPDIR:-/run}/term-shares-table.XXXXXX")
mounted=$(mktemp "${TMPDIR:-/run}/term-shares-mounted.XXXXXX")
complete=false
cleanup() {
  if [ "$complete" = false ]; then
    while IFS= read -r path; do umount "$path" || true; done < "$mounted"
    umount "$index" || true
  fi
  rm -f "$table" "$mounted"
}
trap cleanup EXIT
trap 'exit 1' HUP INT TERM
test -f "$index/mounts" && test ! -L "$index/mounts"
test "$(wc -c < "$index/mounts")" -le 16384
# Validate the entire table before mounting a user directory. No shell evaluation.
awk 'BEGIN { FS="\t" }
  NR == 1 { if ($0 != "1") exit 1; next }
  { if (NF != 2 || length($1) != 35 || $1 !~ /^ts-[0-9a-f]+$/ ||
        length($2) < 1 || length($2) > 64 || $2 !~ /^[A-Za-z][A-Za-z0-9_-]*$/ ||
        tags[$1]++ || names[$2]++ || ++count > 128) exit 1
    print $0 }
  END { if (NR < 2) exit 1 }' "$index/mounts" > "$table"
tab=$(printf '\t')
while IFS="$tab" read -r tag name; do
  path="$destination/$name"
  test ! -L "$path"
  mkdir -p "$path"
  mount -t virtiofs -o nodev,nosuid "$tag" "$path"
  printf '%s\n' "$path" >> "$mounted"
done < "$table"
complete=true
