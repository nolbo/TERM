#!/system/bin/sh
set -eu
mode=${1:?baseline, reference, emulated or restricted}
library=${2:?test APK nativeLibraryDir}
ubuntu=${3:?read-only distribution rootfs}
cache=${4:?writable disposable cache directory}
shift 4
scratch=$(mktemp -d "$cache/emulated-check.XXXXXX")
trap 'rm -rf "$scratch"' EXIT
mkdir -p "$scratch/tmp" "$scratch/usr" "$scratch/lib" "$scratch/dev" "$scratch/proc"
policy=
if [ "$mode" = restricted ]; then policy=$library/libandroid-policy-test.so; fi
engine=$library/libproot.so
dso=$library/libproot-faststat.so
links=--link2symlink
if [ "$mode" = reference ]; then engine=$library/libproot-reference.so; dso=$library/libfaststat-reference.so; links=; fi
if [ "$mode" = baseline ]; then engine=$library/libproot-baseline.so; fi
if [ "$mode" = baseline ]; then preload=; else preload=/usr/local/lib/terminal-faststat.so; fi
env PROOT_TMP_DIR="$cache" PROOT_LOADER="$library/libproot-loader.so" \
PROOT_STAT_ENTRY=1 PROOT_STAT_KERNEL=1 PROOT_PATH_KERNEL=1 PROOT_MUTATION_ENTRY=1 PROOT_RENAME_LOCAL=1 \
PROOT_FSTAT_ENTRY=1 PROOT_FASTSTAT=2 PROOT_FASTFD=1 PROOT_FASTCLOSE=1 PROOT_FASTPATH=1 PROOT_TCGETS_DIRECT=1 PROOT_ID_ENTRY=1 \
/system/bin/sh -c 'exec "$@"' runner $policy "$engine" $links -0 -r "$scratch" -b "$ubuntu/usr:/usr" -b "$ubuntu/usr/lib:/lib" -b "$ubuntu/usr/bin:/bin" -b "$ubuntu/etc:/etc" -b /dev -b /proc \
-b "$dso:/usr/local/lib/terminal-faststat.so" -w /tmp /usr/bin/env -i PATH=/usr/bin:/bin \
LD_PRELOAD="$preload" PROOT_FASTFD=1 PROOT_FASTCLOSE=1 PROOT_FASTOPEN=1 PROOT_FASTCREATE=1 \
PROOT_FASTUNLINK=1 PROOT_FASTSTAT_PATH=0 PROOT_FASTSTAT_SHARED=1 PROOT_TEST_EMULATED_LINKS=1 PROOT_TEST_LIBRARY=/usr/local/lib/terminal-faststat.so PROOT_STDIO_BUFFER=1 PROOT_FASTSTAT_REPORT="${DIAGNOSTIC:-0}" /usr/bin/python3 - "$@" 2>&1
