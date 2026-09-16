#!/usr/bin/env bash
set -euo pipefail
source_directory=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
output_directory=${1:?usage: notify/build.sh OUTPUT_DIRECTORY}
[[ $(uname -m) == aarch64 ]] || { echo 'ARM64 glibc host required' >&2; exit 1; }
mkdir -p "$output_directory"
clang -O2 -Wall -Wextra -Werror -static -Wl,-z,noexecstack,-z,max-page-size=16384 \
    "$source_directory/open-test.c" -o "$output_directory/notify-open-test"
repository_root=$(CDPATH= cd -- "$source_directory/../../.." && pwd)
ndk_root=${ANDROID_NDK_ROOT:-"$repository_root/.android-sdk/ndk/28.0.13004108"}
llvm_data_root="$ndk_root/toolchains/llvm/prebuilt/linux-x86_64"
clang --target=aarch64-linux-android31 --sysroot="$llvm_data_root/sysroot" \
    -resource-dir="$llvm_data_root/lib/clang/19" -rtlib=compiler-rt \
    -O2 -Wall -Wextra -Werror -fPIE -pie -Wl,-z,noexecstack,-z,max-page-size=16384 \
    "$source_directory/open-test.c" -o "$output_directory/notify-open-android-test"
