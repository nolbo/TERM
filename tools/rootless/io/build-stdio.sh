#!/usr/bin/env bash
set -euo pipefail
source_directory=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
output_directory=${1:?usage: build-stdio.sh OUTPUT_DIRECTORY}
mkdir -p "$output_directory"
cc -O2 -Wall -Wextra -Werror -shared -fPIC -pthread \
    "$source_directory/stdio-buffer.c" -ldl -o "$output_directory/stdio-buffer.so"
cc -O2 -Wall -Wextra -Werror -pthread \
    "$source_directory/stdio-test.c" -ldl -o "$output_directory/stdio-test"
cc -O2 -Wall -Wextra -Werror \
    "$source_directory/stdio-probe.c" -o "$output_directory/stdio-probe"
cc -O2 -Wall -Wextra -Werror \
    "$source_directory/stdio-small-probe.c" -o "$output_directory/stdio-small-probe"
PROOT_STDIO_BUFFER=1 LD_PRELOAD="$output_directory/stdio-buffer.so" "$output_directory/stdio-test"
cc -O2 -Wall -Wextra -Werror -shared -fPIC -pthread \
    "$source_directory/stdio-buffer.c" "$source_directory/stdio-check-counter.c" \
    -Wl,--wrap=fcntl,--wrap=fstat -ldl -o "$output_directory/stdio-counted.so"
cc -O2 -Wall -Wextra -Werror "$source_directory/stdio-pool-test.c" \
    -ldl -o "$output_directory/stdio-pool-test"
PROOT_STDIO_BUFFER=1 LD_PRELOAD="$output_directory/stdio-counted.so" "$output_directory/stdio-pool-test"
cc -O2 -Wall -Wextra -Werror -shared -fPIC -pthread \
    "$source_directory/stdio-close-gate.c" -ldl -o "$output_directory/stdio-close-gate.so"
cc -O2 -Wall -Wextra -Werror -pthread "$source_directory/stdio-lifetime-test.c" \
    -ldl -o "$output_directory/stdio-lifetime-test"
PROOT_STDIO_BUFFER=1 LD_PRELOAD="$output_directory/stdio-buffer.so:$output_directory/stdio-close-gate.so" \
    "$output_directory/stdio-lifetime-test"
