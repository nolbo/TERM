#!/bin/sh
# Disposable fake-root file-operation regression; run inside the selected guest.
set -eu
umask 022
work=$(mktemp -d "${1:-/tmp}/terminal-path-kernel-XXXXXX")
trap 'chmod -R u+rwX "$work"; rm -rf "$work"' EXIT
mkdir "$work/dir"
chmod 0500 "$work/dir"
printf data > "$work/dir/file"
printf 'parent mode after create: %s\n' "$(stat -c %a "$work/dir")"
chmod 000 "$work/dir/file"
test "$(cat "$work/dir/file")" = data
printf more >> "$work/dir/file"
printf 'file mode after write: %s\n' "$(stat -c %a "$work/dir/file")"
mv "$work/dir/file" "$work/dir/moved"
ln "$work/dir/moved" "$work/dir/hard"
test "$(stat -c %h "$work/dir/hard")" = 2
touch "$work/dir/hard"
ln -s dir "$work/alias"
test "$(cat "$work/alias/moved")" = datamore
ln -s "$work/dir/new" "$work/dangling"
printf target > "$work/dangling"
test "$(cat "$work/dir/new")" = target
test ! -e "$work/dir/absent"
if cat "$work/dir/moved/child" 2>/dev/null; then exit 1; fi
if cat "$work/dir/moved/" 2>/dev/null; then exit 1; fi
test "$(cat "$work/dir/../dir/new")" = target
test "$(cat "$work//dir/new")" = target
rm "$work/dir/moved"
test "$(cat "$work/dir/hard")" = datamore
printf 'PASS kernel paths, permissions, mutations and symlink fallback\n'
