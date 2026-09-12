#!/usr/bin/env bash
## Shared Android/Bionic Rust linker wrapper; not an on-device toolchain installer.
# Repository-selected NDK headers/libraries with the existing ARM64 host clang.
set -euo pipefail
ndk=${TERMINAL_ANDROID_NDK:?TERMINAL_ANDROID_NDK must be the repository-selected NDK}
data="$ndk/toolchains/llvm/prebuilt/linux-x86_64"
if [[ $(uname -m) == aarch64 ]]; then
    exec clang --target=aarch64-linux-android31 "--sysroot=$data/sysroot" \
        "-resource-dir=$data/lib/clang/19" -rtlib=compiler-rt "$@"
else
    exec "$data/bin/aarch64-linux-android31-clang" "$@"
fi
