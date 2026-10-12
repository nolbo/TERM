#!/usr/bin/env bash
# Build-host only. Never modify Cargo's shared registry cache.
set -euo pipefail
build=${1:?build directory required}
patch_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
mkdir -p "$build/sources" "$build/idevice"
archive="$build/sources/idevice-0.1.65.crate"
digest=7484b3a39a089068167a8ab0b3da6a05b5f6f8969daf1a893da1c7087cb2e09a
if [[ ! -f "$archive" ]]; then
    curl --proto '=https' --proto-redir '=https' -fSL --connect-timeout 20 --max-time 180 \
        https://static.crates.io/crates/idevice/idevice-0.1.65.crate -o "$archive.download"
    printf '%s  %s\n' "$digest" "$archive.download" | sha256sum -c -
    mv "$archive.download" "$archive"
fi
printf '%s  %s\n' "$digest" "$archive" | sha256sum -c -
tar -xzf "$archive" --strip-components=1 -C "$build/idevice"
patch --silent --batch --fuzz=0 -d "$build/idevice" -p1 < "$patch_dir/bounds.patch"
# The registry crate omits the repository-root license. Preserve the exact
# LICENSE.txt from its .cargo_vcs_info revision 2bc6a05c80daaf8583884cf7f2d2563be17e6c2d.
cp "$patch_dir/LICENSE.txt" "$build/idevice/LICENSE.txt"
