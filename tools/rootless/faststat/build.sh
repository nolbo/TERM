#!/usr/bin/env bash
# Build the guest glibc DSO/driver using the existing ARM64 host toolchain.
set -euo pipefail
source_directory=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
output_directory=${1:?usage: faststat/build.sh OUTPUT_DIRECTORY}
[[ $(uname -m) == aarch64 ]] || { echo "ARM64 glibc host required" >&2; exit 1; }
mkdir -p "$output_directory"
extra_sources=()
if [[ ${PROOT_BUILD_STDIO:-0} == 1 ]]; then
    # Explicit experimental build only; ordinary payload bytes stay unchanged.
    extra_sources+=("$source_directory/../io/stdio-buffer.c")
fi
clang -O2 -Wall -Wextra -Werror -fPIC -shared -pthread \
    -Wl,-z,now,-z,noexecstack,-z,max-page-size=16384 \
    "$source_directory/faststat.c" "${extra_sources[@]}" -o "$output_directory/libproot-faststat.so"
if [[ ${PROOT_BUILD_TEST_PROBES:-0} == 1 ]]; then
    clang -O2 -Wall -Wextra -Werror -fPIC -shared -pthread -DPROOT_FASTSTAT_TEST_PROBES \
        -Wl,-z,now,-z,noexecstack,-z,max-page-size=16384 \
        "$source_directory/faststat.c" "${extra_sources[@]}" -o "$output_directory/libproot-faststat-probe.so"
    clang -O2 -Wall -Wextra -Werror -pthread -Wl,-z,noexecstack,-z,max-page-size=16384 \
        "$source_directory/fallback-cost-test.c" -ldl -o "$output_directory/fallback-cost-test"
fi
clang -O2 -Wall -Wextra -Werror -Wl,-z,noexecstack,-z,max-page-size=16384 \
    "$source_directory/test.c" -ldl -pthread -o "$output_directory/faststat-test"
clang -O2 -Wall -Wextra -Werror -Wl,-z,noexecstack,-z,max-page-size=16384 \
    "$source_directory/open-test.c" -pthread -o "$output_directory/open-test"
clang -O2 -Wall -Wextra -Werror -Wl,-z,noexecstack,-z,max-page-size=16384 \
    "$source_directory/trampoline-test.c" -ldl -o "$output_directory/trampoline-test"
clang -O2 -Wall -Wextra -Werror -static -Wl,-z,noexecstack,-z,max-page-size=16384 \
    "$source_directory/stat-entry-test.c" -o "$output_directory/stat-entry-test"
clang -O2 -Wall -Wextra -Werror -Wl,-z,noexecstack,-z,max-page-size=16384 \
    "$source_directory/fd-test.c" -Wl,--export-dynamic-symbol=munmap,--export-dynamic-symbol=pthread_key_create,--export-dynamic-symbol=fcntl -ldl -pthread -o "$output_directory/fd-test"
clang -O2 -Wall -Wextra -Werror -Wl,-z,noexecstack,-z,max-page-size=16384 \
    "$source_directory/close-fallback-test.c" -ldl -o "$output_directory/close-fallback-test"
clang -O2 -Wall -Wextra -Werror -Wl,-z,noexecstack,-z,max-page-size=16384 \
    "$source_directory/single-check-test.c" -ldl -pthread -o "$output_directory/single-check-test"
clang -O2 -Wall -Wextra -Werror -static -pthread -Wl,-z,noexecstack,-z,max-page-size=16384 \
    "$source_directory/emulated-fstat-entry-test.c" -o "$output_directory/emulated-fstat-entry-test"
clang -O2 -Wall -Wextra -Werror -Wl,-z,noexecstack,-z,max-page-size=16384 \
    "$source_directory/fd-utimens-test.c" -o "$output_directory/fd-utimens-test"
clang -O2 -Wall -Wextra -Werror -static -Wl,-z,noexecstack,-z,max-page-size=16384 \
    "$source_directory/fd-utimens-test.c" -o "$output_directory/fd-utimens-test-static"
clang -O2 -Wall -Wextra -Werror -Wl,-z,noexecstack,-z,max-page-size=16384 \
    "$source_directory/identity-regression-test.c" -o "$output_directory/identity-regression-test"
clang -O2 -Wall -Wextra -Werror -static -Wl,-z,noexecstack,-z,max-page-size=16384 \
    "$source_directory/identity-regression-test.c" -o "$output_directory/identity-regression-test-static"
clang -O2 -Wall -Wextra -Werror -Wl,-z,noexecstack,-z,max-page-size=16384 \
    "$source_directory/link-mutation-test.c" -o "$output_directory/link-mutation-test"
clang -O2 -Wall -Wextra -Werror -static -Wl,-z,noexecstack,-z,max-page-size=16384 \
    "$source_directory/link-mutation-test.c" -o "$output_directory/link-mutation-test-static"
