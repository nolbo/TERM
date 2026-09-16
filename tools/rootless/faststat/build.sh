#!/usr/bin/env bash
# Build the guest glibc DSO/driver using the existing ARM64 host toolchain.
set -euo pipefail
source_directory=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
output_directory=${1:?usage: faststat/build.sh OUTPUT_DIRECTORY}
[[ $(uname -m) == aarch64 ]] || { echo "ARM64 glibc host required" >&2; exit 1; }
mkdir -p "$output_directory"
clang -O2 -Wall -Wextra -Werror -fPIC -shared \
    -Wl,-z,noexecstack,-z,max-page-size=16384 \
    "$source_directory/faststat.c" -o "$output_directory/libproot-faststat.so"
clang -O2 -Wall -Wextra -Werror -Wl,-z,noexecstack,-z,max-page-size=16384 \
    "$source_directory/test.c" -ldl -pthread -o "$output_directory/faststat-test"
clang -O2 -Wall -Wextra -Werror -Wl,-z,noexecstack,-z,max-page-size=16384 \
    "$source_directory/open-test.c" -pthread -o "$output_directory/open-test"
clang -O2 -Wall -Wextra -Werror -Wl,-z,noexecstack,-z,max-page-size=16384 \
    "$source_directory/trampoline-test.c" -ldl -o "$output_directory/trampoline-test"
clang -O2 -Wall -Wextra -Werror -static -Wl,-z,noexecstack,-z,max-page-size=16384 \
    "$source_directory/stat-entry-test.c" -o "$output_directory/stat-entry-test"
