#!/usr/bin/env bash
set -euo pipefail
root=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
cargo_bin=${CARGO:-$(command -v cargo || true)}
if [[ -z "$cargo_bin" && -x "$HOME/.cargo/bin/cargo" ]]; then cargo_bin="$HOME/.cargo/bin/cargo"; fi
[[ -x "$cargo_bin" ]] || { echo "Rust/Cargo 1.89+ is required; no toolchain is downloaded automatically." >&2; exit 1; }
[[ $(uname -m) == aarch64 ]] || { echo "This entry probe currently requires an ARM64 Linux host." >&2; exit 1; }
target=${UE_PROBE_TARGET:-aarch64-unknown-linux-gnu}
probe=${UE_PROBE_KIND:-entry}
case "$probe" in entry|loader) ;; *) echo "Unsupported probe kind: $probe" >&2; exit 1 ;; esac
case "$target" in aarch64-unknown-linux-gnu|aarch64-linux-android) ;; *) echo "Unsupported probe target: $target" >&2; exit 1 ;; esac
"$cargo_bin" rustc --offline --manifest-path "$root/native/userspace-engine/Cargo.toml" \
  --release -p ue-runtime --bin "ue-$probe-probe" --features "$probe-probe" --target "$target" -- \
  -C linker=clang -C relocation-model=static \
  -C link-arg=-fuse-ld=lld -C link-arg=-nostdlib -C link-arg=-static \
  -C link-arg=-no-pie -C link-arg=-Wl,-e,_start \
  -C link-arg=-Wl,-z,noexecstack -C link-arg=-Wl,-z,max-page-size=16384
binary="$root/native/userspace-engine/target/$target/release/ue-$probe-probe"
python3 "$root/tools/userspace-engine/check-probe-elf.py" "$binary"
printf '%s\n' "$binary"
