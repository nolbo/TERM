#!/system/bin/sh
# Run via run-as dev.terminal.app; argument is the test APK nativeLibraryDir.
# Only the unique temporary root is mutated. Existing Ubuntu supplies glibc.
set -eu
library_directory=${1:?test APK nativeLibraryDir required}
ubuntu=/data/user/0/dev.terminal.app/files/rootless/ubuntu/rootfs
scratch=$(mktemp -d /data/user/0/dev.terminal.app/cache/stdio-compose.XXXXXX)
trap 'rm -rf -- "$scratch"' EXIT
mkdir -p "$scratch/tmp" "$scratch/usr" "$scratch/lib" "$scratch/dev" "$scratch/proc"
stdio=/usr/local/lib/terminal-stdio.so
faststat=/usr/local/lib/terminal-faststat.so
case ${2:-split} in
    split) set -- "$stdio:$faststat" "$faststat:$stdio" ;;
    combined) set -- "$faststat" ;;
    *) echo 'Expected split or combined library mode' >&2; exit 2 ;;
esac
for preload in "$@"; do
    for fastfd in 0 1; do
        env PROOT_TMP_DIR=/data/user/0/dev.terminal.app/cache \
            PROOT_LOADER="$library_directory/libproot-loader.so" \
            PROOT_STAT_ENTRY=1 PROOT_STAT_KERNEL=1 PROOT_PATH_KERNEL=1 \
            PROOT_MUTATION_ENTRY=1 PROOT_FSTAT_ENTRY=1 PROOT_FASTSTAT=2 \
            PROOT_FASTFD=1 PROOT_FASTCLOSE=1 PROOT_FASTPATH=1 \
            PROOT_TCGETS_DIRECT=1 PROOT_ID_ENTRY=1 \
            /system/bin/sh -c 'exec "$@"' runner \
            "$library_directory/libproot.so" -0 -r "$scratch" \
            -b "$ubuntu/usr:/usr" -b "$ubuntu/usr/lib:/lib" -b /dev -b /proc \
            -b "$library_directory/libstdio-buffer.so:$stdio" \
            -b "$library_directory/libproot-faststat.so:$faststat" \
            -b "$library_directory/libstdio-test.so:/usr/local/bin/terminal-stdio-test" \
            -w /tmp /usr/bin/env -i PATH=/usr/bin:/bin \
            LD_PRELOAD="$preload" PROOT_STDIO_BUFFER=1 STDIO_EXPECT_FASTFD="$fastfd" \
            PROOT_FASTFD="$fastfd" PROOT_FASTCLOSE=1 PROOT_FASTOPEN=1 \
            PROOT_FASTCREATE=1 PROOT_FASTUNLINK=1 PROOT_FASTSTAT_PATH=0 \
            PROOT_FASTSTAT_SHARED=1 /usr/local/bin/terminal-stdio-test
    done
done
