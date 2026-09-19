#!/system/bin/sh
# Run via run-as dev.terminal.app; argument is the test APK nativeLibraryDir.
# Only the unique temporary root is mutated. The selected existing distribution supplies glibc.
set -eu
library_directory=${1:?test APK nativeLibraryDir required}
distribution=${3:-ubuntu}
case $distribution in ubuntu|debian) ;; *) echo "Expected ubuntu or debian" >&2; exit 2 ;; esac
guest=/data/user/0/dev.terminal.app/files/rootless/$distribution/rootfs
scratch=$(mktemp -d /data/user/0/dev.terminal.app/cache/stdio-compose.XXXXXX)
trap 'rm -rf -- "$scratch"' EXIT
mkdir -p "$scratch/tmp" "$scratch/usr" "$scratch/lib" "$scratch/dev" "$scratch/proc"
stdio=/usr/local/lib/terminal-stdio.so
faststat=/usr/local/lib/terminal-faststat.so
case ${2:-split} in
    split) set -- "$stdio:$faststat" "$faststat:$stdio"; workload=streams ;;
    combined) set -- "$faststat"; workload=streams ;;
    combined-write) set -- "$faststat"; workload=small-write ;;
    combined-semantics) set -- "$faststat"; workload=semantics ;;
    combined-applications) set -- "$faststat"; workload=applications ;;
    *) echo 'Expected split, combined, combined-write, combined-semantics or combined-applications mode' >&2; exit 2 ;;
esac
for preload in "$@"; do
    case $workload in
        streams|semantics) sequence='0 1' ;;
        applications) sequence='0' ;;
        *) sequence='0 1 1 0 0 1' ;;
    esac
    for setting in $sequence; do
        argument=write
        semantics_fixture=libstdio-test.so
        if [ "$workload" = streams ]; then
            fastfd=$setting
            buffering=1
            executable=/usr/local/bin/terminal-stdio-test
        elif [ "$workload" = semantics ]; then
            fastfd=1
            buffering=$setting
            executable=/usr/local/bin/terminal-stdio-semantics-test
            semantics_fixture=libstdio-semantics-test.so
        elif [ "$workload" = applications ]; then
            fastfd=1
            buffering=0
            executable=/usr/bin/python3
            argument=-
        else
            fastfd=1
            buffering=$setting
            executable=/usr/local/bin/terminal-stdio-small-probe
            echo "PROOT_STDIO_BUFFER=$buffering"
        fi
        env PROOT_TMP_DIR=/data/user/0/dev.terminal.app/cache \
            PROOT_LOADER="$library_directory/libproot-loader.so" \
            PROOT_STAT_ENTRY=1 PROOT_STAT_KERNEL=1 PROOT_PATH_KERNEL=1 \
            PROOT_MUTATION_ENTRY=1 PROOT_FSTAT_ENTRY=1 PROOT_FASTSTAT=2 \
            PROOT_FASTFD=1 PROOT_FASTCLOSE=1 PROOT_FASTPATH=1 \
            PROOT_TCGETS_DIRECT=1 PROOT_ID_ENTRY=1 \
            /system/bin/sh -c 'exec "$@"' runner \
            "$library_directory/libproot.so" -0 -r "$scratch" \
            -b "$guest/usr:/usr" -b "$guest/usr/lib:/lib" -b /dev -b /proc \
            -b "$library_directory/libstdio-buffer.so:$stdio" \
            -b "$library_directory/libproot-faststat.so:$faststat" \
            -b "$library_directory/libstdio-test.so:/usr/local/bin/terminal-stdio-test" \
            -b "$library_directory/libstdio-small-probe.so:/usr/local/bin/terminal-stdio-small-probe" \
            -b "$library_directory/$semantics_fixture:/usr/local/bin/terminal-stdio-semantics-test" \
            -w /tmp /usr/bin/env -i PATH=/usr/bin:/bin \
            LD_PRELOAD="$preload" PROOT_STDIO_BUFFER="$buffering" STDIO_EXPECT_FASTFD="$fastfd" \
            PROOT_FASTFD="$fastfd" PROOT_FASTCLOSE=1 PROOT_FASTOPEN=1 \
            PROOT_FASTCREATE=1 PROOT_FASTUNLINK=1 PROOT_FASTSTAT_PATH=0 \
            PROOT_FASTSTAT_SHARED=1 "$executable" "$argument"
    done
done
