#!/bin/sh
# Run inside the selected guest. Uses a disposable package root and database.
set -eu
umask 022
work=$(mktemp -d "${TMPDIR:-/tmp}/terminal-native-package-XXXXXX")
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/pkg/DEBIAN" "$work/pkg/usr/share/terminal-link-probe" "$work/target/var/lib/dpkg/updates"
touch "$work/target/var/lib/dpkg/status"
for version in 1 2; do
  printf 'Package: terminal-link-probe\nVersion: %s\nArchitecture: all\nMaintainer: Test <test@example.invalid>\nDescription: disposable link probe\n' "$version" > "$work/pkg/DEBIAN/control"
  rm -f "$work/pkg/usr/share/terminal-link-probe/a" "$work/pkg/usr/share/terminal-link-probe/b"
  printf '%s\n' "$version" > "$work/pkg/usr/share/terminal-link-probe/a"
  ln "$work/pkg/usr/share/terminal-link-probe/a" "$work/pkg/usr/share/terminal-link-probe/b"
  dpkg-deb --build "$work/pkg" "$work/package.deb" >/dev/null
  dpkg --root="$work/target" --install "$work/package.deb" >/dev/null
  test "$(cat "$work/target/usr/share/terminal-link-probe/b")" = "$version"
  test "$(stat -c %h "$work/target/usr/share/terminal-link-probe/a")" = 2
  test ! -L "$work/target/usr/share/terminal-link-probe/a"
done
dpkg --root="$work/target" --remove terminal-link-probe >/dev/null
test ! -e "$work/target/usr/share/terminal-link-probe/a"
test ! -e "$work/target/usr/share/terminal-link-probe/b"
printf 'PASS isolated dpkg install upgrade remove with native hardlinks\n'
