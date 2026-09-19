#!/bin/sh
# Run with the candidate preloaded by the guest launcher, never the Android host.
set -eu
work=$(mktemp -d /tmp/terminal-stdio-programs-XXXXXX)
trap 'rm -rf -- "$work"' EXIT HUP INT TERM
awk 'BEGIN { for (i=10000;i>=1;i--) printf "%08d payload\n", i }' > "$work/input"
LC_ALL=C sort "$work/input" > "$work/sorted"
awk 'BEGIN { for (i=1;i<=10000;i++) printf "%08d payload\n", i }' > "$work/expected"
cmp "$work/sorted" "$work/expected"
sed -n '1,3p' "$work/sorted" > "$work/first"
printf '00000001 payload\n00000002 payload\n00000003 payload\n' > "$work/expected-first"
cmp "$work/first" "$work/expected-first"
grep -c payload "$work/sorted" | grep -qx 10000
gzip -c "$work/sorted" > "$work/sorted.gz"
gzip -dc "$work/sorted.gz" > "$work/expanded"
cmp "$work/sorted" "$work/expanded"
mkdir "$work/extracted"
tar -cf "$work/archive.tar" -C "$work" sorted first
tar -xf "$work/archive.tar" -C "$work/extracted"
cmp "$work/sorted" "$work/extracted/sorted"
cmp "$work/first" "$work/extracted/first"
test "$(dpkg-query -W -f='${db:Status-Status}' bash)" = installed
printf 'PASS stdio programs: awk, sort, sed, grep, gzip, tar, cmp, dpkg-query\n'
