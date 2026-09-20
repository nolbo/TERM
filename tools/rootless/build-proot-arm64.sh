#!/usr/bin/env bash
set -euo pipefail

repository_root=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
output_directory=${1:-}
if [[ -z "$output_directory" ]]; then
    echo "usage: $0 OUTPUT_DIRECTORY" >&2
    exit 2
fi

ndk_root=${ANDROID_NDK_ROOT:-"$repository_root/.android-sdk/ndk/28.0.13004108"}
llvm_data_root="$ndk_root/toolchains/llvm/prebuilt/linux-x86_64"
if [[ $(uname -m) != "aarch64" ]]; then
    echo "This repository's native-Clang PRoot build is supported on an aarch64 Linux host." >&2
    exit 1
fi
for required in curl sha256sum unzip dpkg-deb patch make clang llvm-ar llvm-strip llvm-objcopy llvm-objdump readelf; do
    command -v "$required" >/dev/null || { echo "missing build tool: $required" >&2; exit 1; }
done
[[ -d "$llvm_data_root/sysroot" ]] || { echo "NDK r28 sysroot not found: $llvm_data_root/sysroot" >&2; exit 1; }

build_directory=$(mktemp -d /tmp/droid-proot-build.XXXXXX)
trap 'rm -rf -- "$build_directory"' EXIT
downloads="$build_directory/downloads"
prefix="$build_directory/prefix"
mkdir -p "$downloads" "$prefix" "$output_directory"

download() {
    local url=$1 sha256=$2 destination=$3
    # Reuse explicitly supplied inputs only after checking their pinned digest.
    local cached="${PROOT_DOWNLOAD_CACHE:-}/$(basename -- "$url")"
    if [[ -n ${PROOT_DOWNLOAD_CACHE:-} && -f "$cached" ]]; then
        printf '%s  %s\n' "$sha256" "$cached" | sha256sum -c -
        cp -- "$cached" "$destination"
    else
        curl -fsSL --retry 3 --max-redirs 10 -o "$destination" "$url"
    fi
    printf '%s  %s\n' "$sha256" "$destination" | sha256sum -c -
}

download \
    "https://github.com/termux/proot/archive/refs/tags/v5.1.107.92.zip" \
    "29385d1ddb619a9c4449ab512bfd55032034b22f724ddf98fc95ff300ea32135" \
    "$downloads/proot.zip"
download \
    "https://packages.termux.dev/apt/termux-main/pool/main/libt/libtalloc/libtalloc_2.4.3_aarch64.deb" \
    "ac81ad623d74c209718b9f3acb2dd702cc8a88c431e820d212229910b4db29da" \
    "$downloads/libtalloc.deb"
download \
    "https://packages.termux.dev/apt/termux-main/pool/main/libt/libtalloc-static/libtalloc-static_2.4.3_aarch64.deb" \
    "a2f3bb400395520cc1380626907e61ec59bd072ec2209fbff9bf21e167c5be2e" \
    "$downloads/libtalloc-static.deb"
download \
    "https://packages.termux.dev/apt/termux-main/pool/main/liba/libandroid-shmem/libandroid-shmem_0.7_aarch64.deb" \
    "0da3a24d558b93c92bcf8d611e0826a99ff96e396b148e6cdf33b47c47c57ff6" \
    "$downloads/libandroid-shmem.deb"
download \
    "https://packages.termux.dev/apt/termux-main/pool/main/liba/libandroid-shmem-static/libandroid-shmem-static_0.7_aarch64.deb" \
    "333b092c98b2da88837e1ae41aadb05096761f4a4b4530c85d99f22dc8bdb317" \
    "$downloads/libandroid-shmem-static.deb"

unzip -q "$downloads/proot.zip" -d "$build_directory"
for package in libtalloc libtalloc-static libandroid-shmem libandroid-shmem-static; do
    dpkg-deb -x "$downloads/$package.deb" "$prefix"
done
patch -d "$build_directory/proot-5.1.107.92" -p1 < "$repository_root/tools/rootless/proot-android.patch"
patch -d "$build_directory/proot-5.1.107.92" -p1 < "$repository_root/tools/rootless/proot-perf.patch"
if [[ ${PROOT_BUILD_FASTSTAT:-0} == 1 ]]; then
    cp "$repository_root/tools/rootless/faststat/open-nofollow.h" "$build_directory/proot-5.1.107.92/src/tracee/open-nofollow.h"
    cp "$repository_root/tools/rootless/faststat/protocol.h" "$build_directory/proot-5.1.107.92/src/tracee/faststat.h"
    cp "$repository_root/tools/rootless/faststat/fastfd.c" "$build_directory/proot-5.1.107.92/src/tracee/fastfd.c"
    patch -d "$build_directory/proot-5.1.107.92" -p1 < "$repository_root/tools/rootless/proot-faststat.patch"
    patch --fuzz=0 -d "$build_directory/proot-5.1.107.92" -p1 < "$repository_root/tools/rootless/proot-emulated-links.patch"
    patch --fuzz=0 -d "$build_directory/proot-5.1.107.92" -p1 < "$repository_root/tools/rootless/proot-single-guard.patch"
fi

termux_prefix="$prefix/data/data/com.termux/files/usr"
if [[ ${PROOT_BUILD_RENAME_LOCAL:-0} == 1 ]]; then
    [[ ${PROOT_BUILD_FASTSTAT:-0} == 1 ]] || { echo 'local rename requires PROOT_BUILD_FASTSTAT=1' >&2; exit 1; }
    patch --fuzz=0 -d "$build_directory/proot-5.1.107.92" -p1 < "$repository_root/tools/rootless/proot-rename-local.patch"
    patch --fuzz=0 -d "$build_directory/proot-5.1.107.92" -p1 < "$repository_root/tools/rootless/proot-unlink-local.patch"
fi
if [[ ${PROOT_BUILD_CLOSE_PROBE:-0} == 1 ]]; then
    [[ ${PROOT_BUILD_FASTSTAT:-0} == 1 ]] || { echo 'close probe requires PROOT_BUILD_FASTSTAT=1' >&2; exit 1; }
    patch -d "$build_directory/proot-5.1.107.92" -p1 < "$repository_root/tools/rootless/proot-close-probe.patch"
    echo 'File-workload close upper-bound probe; not a general-purpose runtime.' >&2
fi
if [[ ${PROOT_BUILD_NOTIFY:-0} == 1 ]]; then
    [[ ${PROOT_BUILD_FASTSTAT:-0} == 1 ]] || { echo 'notify requires PROOT_BUILD_FASTSTAT=1' >&2; exit 1; }
    cp "$repository_root/tools/rootless/notify/open-notify.c" "$build_directory/proot-5.1.107.92/src/tracee/open-notify.c"
    cp "$repository_root/tools/rootless/notify/open-notify.h" "$build_directory/proot-5.1.107.92/src/tracee/open-notify.h"
    patch -d "$build_directory/proot-5.1.107.92" -p1 < "$repository_root/tools/rootless/proot-notify.patch"
fi
source_directory="$build_directory/proot-5.1.107.92/src"
compiler="clang --target=aarch64-linux-android31 --sysroot=$llvm_data_root/sysroot -resource-dir=$llvm_data_root/lib/clang/19"
make -C "$source_directory" -j"$(nproc)" \
    CC="$compiler" \
    STRIP=llvm-strip \
    OBJCOPY=llvm-objcopy \
    OBJDUMP=llvm-objdump \
    CPPFLAGS="-D_FILE_OFFSET_BITS=64 -D_GNU_SOURCE -DARG_MAX=131072 -DVERSION=\\\"5.1.107.92\\\" -I. -I$source_directory -I$termux_prefix/include" \
    CFLAGS="-Wall -Wextra -O2 -fPIC" \
    LDFLAGS="-rtlib=compiler-rt -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384 -Wl,-z,noexecstack $termux_prefix/lib/libtalloc.a $termux_prefix/lib/libandroid-shmem.a -llog -ldl" \
    PROOT_WITH_LIBANDROID_SHMEM=true

llvm-strip -o "$output_directory/proot" "$source_directory/proot"
llvm-strip -o "$output_directory/proot-loader" "$source_directory/loader/loader"
chmod 0755 "$output_directory/proot" "$output_directory/proot-loader"

if [[ ${PROOT_BUILD_FASTSTAT:-0} == 1 ]]; then
    echo "Experimental faststat payload; not the production APK input."
    sha256sum "$output_directory/proot" "$output_directory/proot-loader"
    printf '%s  %s\n' "2567ab8ad3b418a8804696342056052eef37835e50d200e9f4ff48aae564c237" "$output_directory/proot-loader" | sha256sum -c -
else
    printf '%s  %s\n' \
    "9edb5ff81998a990b014797c9acc05f31b9eb5d84cb16105d4e8c1179d7d382b" "$output_directory/proot" \
    "2567ab8ad3b418a8804696342056052eef37835e50d200e9f4ff48aae564c237" "$output_directory/proot-loader" | sha256sum -c -
fi
readelf -lW "$output_directory/proot" "$output_directory/proot-loader" |
    awk '$1 == "LOAD" && $NF != "0x4000" { bad = 1 } END { exit bad }'
echo "Built ARM64 PRoot payloads with verified inputs and 16 KiB alignment in $output_directory"
