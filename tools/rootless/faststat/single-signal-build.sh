#!/usr/bin/env bash
# Test-only injected DSOs. Neither output is a production runtime payload.
set -euo pipefail
source_directory=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
output_directory=${1:?usage: single-signal-build.sh OUTPUT_DIRECTORY}
temporary=$(mktemp -d /tmp/fastfd-signal.XXXXXX)
trap 'rm -rf -- "$temporary"' EXIT
mkdir -p "$temporary/tools/rootless/faststat" "$output_directory"
cp "$source_directory/faststat.c" "$temporary/tools/rootless/faststat/faststat.c"
patch --fuzz=0 -d "$temporary" -p1 < "$source_directory/single-signal-hook.patch"
python3 - "$temporary/tools/rootless/faststat/faststat.c" "$temporary/control.c" <<'PY'
from pathlib import Path
import sys
source = Path(sys.argv[1]).read_text()
guard = '!atomic_load(&identity->single_safe)) goto slow_fd;'
assert source.count(guard) == 1
Path(sys.argv[2]).write_text(source.replace(guard, '0 /* negative control */) goto slow_fd;'))
PY
for variant in guarded control; do
    input="$temporary/tools/rootless/faststat/faststat.c"
    if [[ $variant == control ]]; then input="$temporary/control.c"; fi
    clang -O2 -Wall -Wextra -Werror -fPIC -shared -pthread -I"$source_directory" \
        -Wl,-z,now,-z,noexecstack,-z,max-page-size=16384 \
        "$input" "$source_directory/../io/stdio-buffer.c" \
        -o "$output_directory/signal-$variant.so"
done
clang -O2 -Wall -Wextra -Werror -Wl,-z,noexecstack,-z,max-page-size=16384 \
    -Wl,--export-dynamic-symbol=proot_epoch_test_hook \
    "$source_directory/single-signal-test.c" -o "$output_directory/single-signal-test"
