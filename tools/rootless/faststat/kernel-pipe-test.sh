#!/bin/bash
# Run inside a disposable guest test session; all package state stays temporary.
set -euo pipefail
umask 022
export PATH=/usr/sbin:/usr/bin:/sbin:/bin
work=$(mktemp -d /tmp/terminal-kernel-pipe-XXXXXX)
trap 'rm -rf "$work"' EXIT

for ((i=0; i<20; i++)); do
    test "$(seq 1 10000 | wc -l)" = 10000
    diff <(seq 1 10000) <(seq 1 10000)
    # Early consumer exit must terminate the writer, not leave it blocked.
    set +e
    yes | head -n 1 > "$work/first"
    statuses=("${PIPESTATUS[@]}")
    set -e
    test "${statuses[0]}" = 141
    test "${statuses[1]}" = 0
    test "$(cat "$work/first")" = y
done

# dpkg-deb exercises compression pipes; dpkg installs into an isolated root
# with no maintainer scripts, leaving the real package database untouched.
mkdir -p "$work/package/DEBIAN" "$work/package/usr/share/terminal-pipe-test"
cat > "$work/package/DEBIAN/control" <<'EOF'
Package: terminal-pipe-test
Version: 1.0
Architecture: all
Maintainer: Test <test@example.invalid>
Description: Disposable pipe regression fixture
EOF
seq 1 100000 > "$work/package/usr/share/terminal-pipe-test/content"
dpkg-deb --build "$work/package" "$work/test.deb"
mkdir -p "$work/root/var/lib/dpkg"
touch "$work/root/var/lib/dpkg/status"
dpkg --root="$work/root" --install "$work/test.deb"
cmp "$work/package/usr/share/terminal-pipe-test/content" \
    "$work/root/usr/share/terminal-pipe-test/content"
test "$(dpkg-query --admindir="$work/root/var/lib/dpkg" \
    -W -f='${Status}' terminal-pipe-test)" = 'install ok installed'
dpkg --root="$work/root" --purge terminal-pipe-test
test ! -e "$work/root/usr/share/terminal-pipe-test/content"
echo 'PASS: 20 pipeline/early-exit/process-substitution rounds and isolated dpkg install/purge'
