#!/system/bin/sh
# Test-only runner: APK-owned engines, existing guest /usr input, disposable test writes.
set -eu
mode=${1:?mode required}; shift
library=${1:?APK native library directory required}; shift
storage=${1:?native or emulated storage required}; shift
rootfs=${ROOTLESS_TEST_ROOTFS:?explicit existing guest rootfs is required}
cache=${ROOTLESS_TEST_CACHE:?test cache directory is required}
workloads=${ROOTLESS_TEST_WORKLOADS:-}
tools=${ROOTLESS_TEST_TOOLS:-}
job=${ROOTLESS_TEST_JOB:-}
identity=${ROOTLESS_TEST_IDENTITY:-0:0}
preload=1
case "$mode" in
    baseline) engine="$library/libproot-baseline.so"; dso="$library/libfaststat-baseline.so"; direct=0 ;;
    candidate) engine="$library/libproot.so"; dso="$library/libproot-faststat.so"; direct=1 ;;
    candidate-no-utimens) engine="$library/libproot.so"; dso="$library/libproot-faststat.so"; direct=0 ;;
    candidate-no-preload) engine="$library/libproot.so"; dso="$library/libproot-faststat.so"; direct=1; preload=0 ;;
    *) echo 'unknown engine profile' >&2; exit 2 ;;
esac
case "$storage" in
    native) links= ;;
    emulated) links=--link2symlink ;;
    *) echo 'unknown storage profile' >&2; exit 2 ;;
esac
[ -f "$engine" ] && [ -f "$dso" ] && [ -f "$rootfs/usr/bin/python3" ]
scratch=$(mktemp -d "$cache/rootless-workload.XXXXXX")
if [ "${ROOTLESS_TEST_KEEP_SCRATCH:-0}" = 1 ]; then
    printf 'diagnostic-scratch=%s\n' "$scratch"
else
    trap 'rm -rf -- "$scratch"' EXIT HUP INT TERM
fi
mkdir -p "$scratch/tmp" "$scratch/usr" "$scratch/lib" "$scratch/bin" "$scratch/dev" "$scratch/proc"
# Android cache directories inherit a cache GID and setgid bit. These tests
# create ordinary app-owned files, matching the runtime's private workspace.
chmod 700 "$scratch/tmp"
printf 'fixture\n' > "$scratch/input"
mkfifo "$scratch/tmp/thread-fifo"
set -- "$engine" $links -i "$identity" -r "$scratch" \
    -b "$rootfs/usr:/usr" -b "$rootfs/usr/lib:/lib" -b "$rootfs/usr/bin:/bin" \
    -b "$rootfs/etc:/etc" -b /dev -b /proc \
    -b "$dso:/usr/local/lib/terminal-faststat.so" \
    -b "$library:/fixtures" \
    ${workloads:+-b} ${workloads:+"$workloads:/workloads"} \
    ${tools:+-b} ${tools:+"$tools:/tools"} \
    ${job:+-b} ${job:+"$job:/tmp/job.json"} \
    -w /tmp /usr/bin/env -i \
    PATH=/tools/bin:/usr/sbin:/usr/bin:/sbin:/bin HOME=/tmp LC_ALL=C \
    GIT_EXEC_PATH=/tools/git-core GIT_TEMPLATE_DIR=/tools/templates \
    LD_PRELOAD="$(if [ "$preload" = 1 ]; then printf /usr/local/lib/terminal-faststat.so; fi)" \
    PROOT_FASTFD=1 PROOT_FASTCLOSE=1 PROOT_FASTOPEN=1 PROOT_FASTCREATE=1 \
    PROOT_FASTUNLINK=1 PROOT_FASTSTAT_PATH=0 PROOT_FASTSTAT_SHARED=1 PROOT_STDIO_BUFFER=1 "$@"
env PROOT_TMP_DIR="$cache" PROOT_LOADER="$library/libproot-loader.so" \
    PROOT_STAT_ENTRY=1 PROOT_STAT_KERNEL=1 PROOT_PATH_KERNEL=1 \
    PROOT_MUTATION_ENTRY=1 PROOT_RENAME_LOCAL=1 PROOT_FSTAT_ENTRY=1 \
    PROOT_FASTSTAT=2 PROOT_FASTFD=1 PROOT_FASTCLOSE=1 PROOT_FASTPATH=1 \
    PROOT_TCGETS_DIRECT=1 PROOT_ID_ENTRY=1 PROOT_FD_UTIMENS_DIRECT="$direct" \
    PROOT_PERF="${ROOTLESS_TEST_PERF:-0}" PROOT_PERF_SYSCALLS="${ROOTLESS_TEST_PERF:-0}" \
    /system/bin/sh -c 'exec "$@"' runner "$library/libandroid-policy-test.so" "$@" 2>&1
