#!/usr/bin/env bash
set -euo pipefail
repo=$(CDPATH= cd -- "$(dirname -- "$0")/../../.." && pwd)
build=${1:?usage: build.sh BUILD_DIRECTORY NDK_DIRECTORY [--test]}
export TERMINAL_ANDROID_NDK=$(CDPATH= cd -- "${2:?repository-selected NDK directory}" && pwd)
cargo=${TERMINAL_CARGO:-${CARGO_HOME:-"$HOME/.cargo"}/bin/cargo}
if [[ -z ${TERMINAL_CARGO:-} && ! -x "$cargo" ]]; then cargo=cargo; fi
mkdir -p "$build"
build=$(CDPATH= cd -- "$build" && pwd)
mkdir -p "$build/connector" "$build/jniLibs/arm64-v8a" "$build/assets/licenses/iphone-tools"
cp -R "$repo/tools/iphone/connector/." "$build/connector/"
bash "$repo/tools/iphone/connector/idevice/prepare.sh" "$build"
export CARGO_TARGET_DIR="$build/target"
if [[ ${3:-} == --update-lock ]]; then
    "$cargo" +1.89.0 update --offline --manifest-path "$build/connector/Cargo.toml" -p idevice
    cp "$build/connector/Cargo.lock" "$repo/tools/iphone/connector/Cargo.lock"
    exit
fi
if [[ ${3:-} == --test ]]; then
    "$cargo" +1.89.0 test --locked --manifest-path "$build/connector/Cargo.toml"
    exit
fi
export CC_aarch64_linux_android="$repo/tools/iphone/android-clang.sh"
export AR_aarch64_linux_android=ar
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$repo/tools/iphone/android-clang.sh"
export RUSTFLAGS='-C link-arg=-Wl,-z,max-page-size=16384 -C link-arg=-Wl,-z,common-page-size=16384 -C link-arg=-Wl,-z,relro,-z,now,-z,noexecstack'
"$cargo" +1.89.0 build --locked --release --target aarch64-linux-android --manifest-path "$build/connector/Cargo.toml"
for binary in terminal-usb iphone-tool-probe; do
    cp "$build/target/aarch64-linux-android/release/$binary" "$build/jniLibs/arm64-v8a/lib$binary.so"
    readelf -h "$build/jniLibs/arm64-v8a/lib$binary.so" | sed -n '/Class:/p; /Machine:/p; /Type:/p'
done
(cd "$build" && "$cargo" +1.89.0 vendor --locked --versioned-dirs --manifest-path connector/Cargo.toml vendor) > "$build/vendor-config.toml"
for directory in "$build/vendor/"*; do
    name=$(basename "$directory")
    mkdir -p "$build/assets/licenses/iphone-tools/$name"
    for license in "$directory"/LICENSE* "$directory"/COPYING*; do
        [[ ! -f "$license" ]] || cp "$license" "$build/assets/licenses/iphone-tools/$name/"
    done
done
cp "$repo/tools/iphone/connector/NOTICE.txt" "$build/assets/licenses/iphone-tools/NOTICE.txt"
cp "$repo/tools/iphone/connector/LICENSE" "$build/assets/licenses/iphone-tools/LICENSE.txt"
cp "$build/idevice/LICENSE.txt" "$build/assets/licenses/iphone-tools/idevice-LICENSE.txt"
mkdir -p "$build/distributions"
tar -czf "$build/distributions/iphone-tools-source.tar.gz" -C "$repo" tools/iphone/connector tools/iphone/android-clang.sh \
    -C "$build" idevice sources vendor vendor-config.toml
