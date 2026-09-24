#!/usr/bin/env bash
# No downloaded compiler and no product payload changes.
set -euo pipefail
source_directory=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
repository_root=$(CDPATH= cd -- "$source_directory/../.." && pwd)
output_directory=${1:?usage: build.sh OUTPUT_DIRECTORY}
mkdir -p "$output_directory"
export TERMINAL_ANDROID_NDK=${ANDROID_NDK_ROOT:-"$repository_root/.android-sdk/ndk/28.0.13004108"}
bash "$repository_root/tools/iphone/android-clang.sh" \
    -O2 -Wall -Wextra -Werror -fPIE -pie -Wl,-z,now,-z,noexecstack,-z,max-page-size=16384 \
    "$source_directory/probe.c" "$source_directory/syscall.S" -o "$output_directory/libdirect-linux-probe.so"
sha256sum "$output_directory/libdirect-linux-probe.so"
