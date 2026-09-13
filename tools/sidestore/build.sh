#!/usr/bin/env bash
# Source/crate downloads happen on the build host, never on the Android device.
set -euo pipefail
repo=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
build=${1:?usage: build.sh BUILD_DIRECTORY NDK_DIRECTORY [--update-lock|--test]}
export TERMINAL_ANDROID_NDK=$(CDPATH= cd -- "${2:?NDK_DIRECTORY required}" && pwd)
cargo=${SIDESTORE_CARGO:-${CARGO_HOME:-"$HOME/.cargo"}/bin/cargo}
if [[ -z ${SIDESTORE_CARGO:-} && ! -x "$cargo" ]]; then cargo=cargo; fi
mkdir -p "$build"
build=$(CDPATH= cd -- "$build" && pwd)
mkdir -p "$build/upstream/isideload" "$build/worker" "$build/sources" "$build/jniLibs/arm64-v8a" "$build/assets/licenses/sidestore"
mkdir -p "$build/connector"
cp -R "$repo/tools/iphone/connector/." "$build/connector/"
bash "$repo/tools/iphone/connector/idevice/prepare.sh" "$build"
while read -r name revision digest url; do
    [[ -z "$name" || "$name" == \#* ]] && continue
    archive="$build/sources/$name.tar.gz"
    if [[ ! -f "$archive" ]]; then
        curl -fSL --retry 2 --connect-timeout 20 --max-time 180 "$url" -o "$archive.download"
        printf '%s  %s\n' "$digest" "$archive.download" | sha256sum -c -
        mv "$archive.download" "$archive"
    fi
    printf '%s  %s\n' "$digest" "$archive" | sha256sum -c -
    tar -xzf "$archive" --strip-components=1 -C "$build/upstream/$name"
done < "$repo/tools/sidestore/sources.lock"
patch --silent -d "$build/upstream/isideload" -p1 < "$repo/tools/sidestore/android-tls.patch"
cp -R "$repo/tools/sidestore/worker/." "$build/worker/"
cp "$repo/tools/sidestore/worker/src/android_tls.rs" "$build/upstream/isideload/isideload/src/util/android_tls.rs"
export CARGO_TARGET_DIR="$build/target"
if [[ ${3:-} == --update-lock ]]; then
    "$cargo" +1.89.0 update --offline --manifest-path "$build/worker/Cargo.toml" -p idevice
    cp "$build/worker/Cargo.lock" "$repo/tools/sidestore/worker/Cargo.lock"
    exit
fi
if [[ ${3:-} == --test ]]; then
    "$cargo" +1.89.0 test --locked --manifest-path "$build/worker/Cargo.toml"
    exit
fi
export CC_aarch64_linux_android="$repo/tools/iphone/android-clang.sh"
export AR_aarch64_linux_android=ar
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$repo/tools/iphone/android-clang.sh"
export RUSTFLAGS='-C link-arg=-Wl,-z,max-page-size=16384 -C link-arg=-Wl,-z,common-page-size=16384 -C link-arg=-Wl,-z,relro,-z,now,-z,noexecstack'
"$cargo" +1.89.0 build --locked --release --target aarch64-linux-android --manifest-path "$build/worker/Cargo.toml"
cp "$build/target/aarch64-linux-android/release/sidestore-bootstrap" "$build/jniLibs/arm64-v8a/libisideload.so"
# Remove the old build-output name only; it must not accidentally enter APK packaging.
if [[ -f "$build/jniLibs/arm64-v8a/libsidestore-bootstrap.so" ]]; then unlink "$build/jniLibs/arm64-v8a/libsidestore-bootstrap.so"; fi
readelf -h "$build/jniLibs/arm64-v8a/libisideload.so" | sed -n '/Class:/p; /Machine:/p; /Type:/p'
# Preserve complete corresponding source for MPL and third-party notices.
(cd "$build" && "$cargo" +1.89.0 vendor --locked --versioned-dirs --manifest-path worker/Cargo.toml vendor) > "$build/vendor-config.toml"
for directory in "$build/vendor/"*; do
    name=$(basename "$directory")
    mkdir -p "$build/assets/licenses/sidestore/$name"
    for license in "$directory"/LICENSE* "$directory"/COPYING*; do
        [[ ! -f "$license" ]] || cp "$license" "$build/assets/licenses/sidestore/$name/"
    done
done
cp "$build/upstream/isideload/LICENSE" "$build/assets/licenses/sidestore/isideload-LICENSE.txt"
cp "$repo/tools/sidestore/NOTICE.txt" "$build/assets/licenses/sidestore/NOTICE.txt"
cp "$build/idevice/LICENSE.txt" "$build/assets/licenses/sidestore/idevice-LICENSE.txt"
mkdir -p "$build/distributions"
tar -czf "$build/distributions/sidestore-bootstrap-source.tar.gz" -C "$repo" tools/sidestore tools/iphone/connector tools/iphone/android-clang.sh \
    -C "$build" idevice sources vendor vendor-config.toml
