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
for source in guest exec memory files devices fds network signals threads scratch tasks; do
    bash "$repository_root/tools/iphone/android-clang.sh" \
        -O2 -Wall -Wextra -Werror -fPIE -fno-builtin -fno-stack-protector \
        -mgeneral-regs-only -mno-outline-atomics -c "$source_directory/$source.c" -o "$output_directory/$source.o"
done
bash "$repository_root/tools/iphone/android-clang.sh" -c "$source_directory/copy.S" -o "$output_directory/copy.o"
bash "$repository_root/tools/iphone/android-clang.sh" -c "$source_directory/threads.S" -o "$output_directory/threads-asm.o"
ld -r "$output_directory/copy.o" "$output_directory/guest.o" "$output_directory/exec.o" "$output_directory/memory.o" "$output_directory/files.o" "$output_directory/devices.o" "$output_directory/fds.o" "$output_directory/network.o" "$output_directory/signals.o" "$output_directory/threads.o" "$output_directory/scratch.o" "$output_directory/tasks.o" "$output_directory/threads-asm.o" -o "$output_directory/runtime.o"
# Audit the complete guest-reachable call graph, including image replacement.
unexpected=$(nm -u "$output_directory/runtime.o" | \
    awk '$2 != "dl_host_syscall" && $2 != "dl_host_return" && $2 != "dl_sigreturn" && $2 != "dl_sigreturn_return" { print $2 }')
[[ -z "$unexpected" ]] || { echo "Guest runtime has forbidden imports: $unexpected" >&2; exit 1; }
if ! readelf -s "$output_directory/runtime.o" | awk '$4 == "TLS" { bad = 1 } END { exit bad }'; then
    echo 'Guest runtime TLS-symbol audit failed' >&2
    exit 1
fi
bash "$repository_root/tools/iphone/android-clang.sh" \
    -O2 -Wall -Wextra -Werror -fPIE -fno-builtin -fno-stack-protector -mgeneral-regs-only \
    -nostdlib -static -Wl,--no-pie,--no-dynamic-linker,-e,dl_start,--image-base=0x2000000000,-z,now,-z,noexecstack,-z,max-page-size=16384 \
    "$source_directory/loader.c" "$output_directory/runtime.o" "$source_directory/syscall.S" \
    "$source_directory/enter.S" -o "$output_directory/libdirect-linux-loader.so"
if [[ -n $(nm -u "$output_directory/libdirect-linux-loader.so") ]] || \
        readelf -l "$output_directory/libdirect-linux-loader.so" | rg -q 'INTERP| TLS '; then
    echo 'Freestanding loader import/interpreter audit failed' >&2
    exit 1
fi
if ! readelf -h "$output_directory/libdirect-linux-loader.so" | rg -q 'Type: +EXEC' || \
        ! readelf -r "$output_directory/libdirect-linux-loader.so" | rg -q 'There are no relocations'; then
    echo 'Fixed bootstrap type/relocation audit failed' >&2
    exit 1
fi
sha256sum "$output_directory/libdirect-linux-loader.so"
