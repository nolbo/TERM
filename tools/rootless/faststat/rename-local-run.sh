#!/system/bin/sh
# Test APK nativeLibraryDir, local-path option 0|1, diagnostic counter option 0|1.
# Execute as dev.terminal.app with the Python test/probe on standard input.
set -eu
library_directory=${1:?test APK nativeLibraryDir required}
local_paths=${2:-1}
diagnostics=${3:-0}
if [ "$#" -ge 3 ]; then shift 3; else set --; fi
ubuntu=/data/user/0/dev.terminal.app/files/rootless/ubuntu/rootfs
scratch=$(mktemp -d /data/user/0/dev.terminal.app/cache/rename-local.XXXXXX)
trap 'rm -rf -- "$scratch"' EXIT
mkdir -p "$scratch/tmp" "$scratch/usr" "$scratch/lib" "$scratch/dev" "$scratch/proc"
env PROOT_TMP_DIR=/data/user/0/dev.terminal.app/cache \
    PROOT_LOADER="$library_directory/libproot-loader.so" \
    PROOT_STAT_ENTRY=1 PROOT_STAT_KERNEL=1 PROOT_PATH_KERNEL=1 \
    PROOT_MUTATION_ENTRY=1 PROOT_FSTAT_ENTRY=1 PROOT_FASTSTAT=2 \
    PROOT_FASTFD=1 PROOT_FASTCLOSE=1 PROOT_FASTPATH=1 \
    PROOT_TCGETS_DIRECT=1 PROOT_ID_ENTRY=1 \
    PROOT_RENAME_LOCAL="$local_paths" PROOT_PERF="$diagnostics" \
    /system/bin/sh -c 'exec "$@"' runner \
    "$library_directory/libproot.so" -0 -r "$scratch" \
    -b "$ubuntu/usr:/usr" -b "$ubuntu/usr/lib:/lib" -b /dev -b /proc \
    -b "$library_directory/libproot-faststat.so:/usr/local/lib/terminal-faststat.so" \
    -w /tmp /usr/bin/env -i PATH=/usr/bin:/bin \
    LD_PRELOAD=/usr/local/lib/terminal-faststat.so PROOT_FASTFD=1 \
    PROOT_FASTCLOSE=1 PROOT_FASTOPEN=1 PROOT_FASTCREATE=1 PROOT_FASTUNLINK=1 \
    PROOT_FASTSTAT_PATH=0 PROOT_FASTSTAT_SHARED=1 /usr/bin/python3 - "$@" 2>&1
