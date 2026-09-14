#!/bin/sh
# Link regression subset for distributions without Python. Run inside PRoot.
set -eu
proot_perf_dir=$(mktemp -d)
trap 'rm -rf -- "$proot_perf_dir"' EXIT
printf x > "$proot_perf_dir/a"
ln "$proot_perf_dir/a" "$proot_perf_dir/b"
[ -f "$proot_perf_dir/b" ]
[ ! -L "$proot_perf_dir/b" ]
[ "$(stat -c %h "$proot_perf_dir/b")" = 2 ]
ln -s b "$proot_perf_dir/s"
[ -L "$proot_perf_dir/s" ]
[ -f "$proot_perf_dir/s" ]
mv "$proot_perf_dir/b" "$proot_perf_dir/c"
rm "$proot_perf_dir/a"
[ "$(stat -c %h "$proot_perf_dir/c")" = 1 ]
[ ! -e "$proot_perf_dir/s" ]
[ -L "$proot_perf_dir/s" ]
printf '%s\n' '{"check":"passed","subset":"shell-links"}'
