#!/usr/bin/env bash
set -euo pipefail
root=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
cargo_bin=${CARGO:-$(command -v cargo || true)}
if [[ -z "$cargo_bin" && -x "$HOME/.cargo/bin/cargo" ]]; then cargo_bin="$HOME/.cargo/bin/cargo"; fi
[[ -x "$cargo_bin" ]] || { echo "Cargo 1.89+ is required" >&2; exit 1; }
"$cargo_bin" test --offline --manifest-path "$root/native/userspace-engine/Cargo.toml" --workspace --lib
clang -std=c11 -Wall -Wextra -Werror -fsyntax-only "$root/native/userspace-engine/tests/kernel-layout.c"
"$root/tools/userspace-engine/build-entry-probe.sh"
timeout 15s "$root/native/userspace-engine/target/aarch64-unknown-linux-gnu/release/ue-entry-probe"
