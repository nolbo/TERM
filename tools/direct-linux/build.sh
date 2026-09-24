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
bash "$repository_root/tools/iphone/android-clang.sh" \
    -O2 -Wall -Wextra -Werror -fPIE -fno-builtin -fno-stack-protector \
    -mgeneral-regs-only -c "$source_directory/guest.c" -o "$output_directory/guest.o"
# A future edit must not introduce libc/TLS/allocator calls into signal handling.
unexpected=$(nm -u "$output_directory/guest.o" | \
    awk '$2 != "dl_host_syscall" && $2 != "dl_host_return" && $2 != "dl_sigreturn" { print $2 }')
[[ -z "$unexpected" ]] || { echo "Guest dispatcher has forbidden imports: $unexpected" >&2; exit 1; }
if ! readelf -s "$output_directory/guest.o" | awk '$4 == "TLS" { bad = 1 } END { exit bad }'; then
    echo 'Guest dispatcher TLS-symbol audit failed' >&2
    exit 1
fi
bash "$repository_root/tools/iphone/android-clang.sh" \
    -O2 -Wall -Wextra -Werror -fPIE -pie -Wl,-z,now,-z,noexecstack,-z,max-page-size=16384 \
    "$source_directory/loader.c" "$output_directory/guest.o" "$source_directory/syscall.S" \
    "$source_directory/enter.S" -o "$output_directory/libdirect-linux-loader.so"
sha256sum "$output_directory/libdirect-linux-loader.so"
