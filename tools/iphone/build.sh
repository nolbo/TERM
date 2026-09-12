#!/usr/bin/env bash
# Build from pinned source; no writable-data executable or system package install.
set -euo pipefail
repo=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
build=${1:?usage: build.sh BUILD_DIRECTORY NDK_DIRECTORY}
ndk=${2:?NDK_DIRECTORY required}
mkdir -p "$build"
build=$(CDPATH= cd -- "$build" && pwd)
port="$repo/android-runtime/src/main/iphone"
src="$build/sources"
mkdir -p "$src" "$build/objects" "$build/jniLibs/arm64-v8a" "$build/assets/licenses/iphone"
declare -A roots
while read -r name root digest url; do
    [[ -z "$name" || "$name" == \#* ]] && continue
    archive="$src/$name.tar.gz"
    if [[ ! -f "$archive" ]]; then
        curl -fSL --retry 2 --connect-timeout 20 --max-time 180 "$url" -o "$archive.download"
        printf '%s  %s\n' "$digest" "$archive.download" | sha256sum -c -
        mv "$archive.download" "$archive"
    fi
    printf '%s  %s\n' "$digest" "$archive" | sha256sum -c -
    [[ -d "$src/$root" ]] || tar -xzf "$archive" -C "$src"
    roots[$name]="$src/$root"
    for license in COPYING COPYING.LESSER COPYING.GPLv2 COPYING.GPLv3 LICENSE LICENSE.txt; do
        if [[ -f "$src/$root/$license" ]]; then
            cp "$src/$root/$license" "$build/assets/licenses/iphone/$name-$license.txt"
        fi
    done
done < "$repo/tools/iphone/sources.lock"
data="$ndk/toolchains/llvm/prebuilt/linux-x86_64"
[[ -d "$data/sysroot" ]] || { echo 'NDK sysroot missing' >&2; exit 1; }
if [[ $(uname -m) == aarch64 ]]; then
    compiler=(clang --target=aarch64-linux-android31 "--sysroot=$data/sysroot" "-resource-dir=$data/lib/clang/19" -rtlib=compiler-rt)
else
    compiler=("$data/bin/aarch64-linux-android31-clang")
fi
flags=(-O2 -fPIE -fvisibility=hidden -ffunction-sections -fdata-sections -fstack-protector-strong -D_FORTIFY_SOURCE=2 -Wno-deprecated-declarations)
objects=()
inc=(-I"$repo/tools/iphone" -I"$port" -I"${roots[libusb]}/libusb" -I"${roots[libplist]}/include" -I"${roots[glue]}/include" -I"${roots[libusbmuxd]}/include" -I"${roots[limd]}/include" -I"${roots[limd]}" -I"${roots[mbedtls]}/include" -I"${roots[usbmuxd]}/src")
compile() {
    local group=$1
    shift
    mkdir -p "$build/objects/$group"
    for source in "$@"; do
        if [[ "$source" == *.c ]]; then objects+=("$build/objects/$group/$(basename "${source%.c}").o"); fi
    done
    local signature
    signature=$({
        printf '%s\0' "${compiler[@]}" "${flags[@]}" "$@"
        sha256sum "$repo/tools/iphone/sources.lock"
        for source in "$@"; do [[ "$source" != *.c ]] || sha256sum "$source"; done
        if [[ "$group" != tls && "$group" != usb ]]; then sha256sum "$repo/tools/iphone/config.h" "$port"/*.h; fi
    } | sha256sum)
    if [[ -f "$build/objects/$group/signature" && $(<"$build/objects/$group/signature") == "$signature" ]]; then return; fi
    (cd "$build/objects/$group"; "${compiler[@]}" "${flags[@]}" "$@" -c)
    printf '%s\n' "$signature" > "$build/objects/$group/signature"
}
compile usb -I"${roots[libusb]}/android" -I"${roots[libusb]}/libusb" \
    "${roots[libusb]}"/libusb/{core,descriptor,hotplug,io,sync,strerror}.c \
    "${roots[libusb]}"/libusb/os/{linux_usbfs,events_posix,threads_posix,linux_netlink}.c
compile tls -I"${roots[mbedtls]}/include" -I"${roots[mbedtls]}/library" "${roots[mbedtls]}"/library/*.c
compile plist "${inc[@]}" -DHAVE_CONFIG_H '-DPACKAGE_VERSION="2.7.0"' -I"${roots[libplist]}/src" -I"${roots[libplist]}/libcnary/include" \
    "${roots[libplist]}"/src/*.c "${roots[libplist]}"/libcnary/{node,node_list}.c
compile glue "${inc[@]}" -DHAVE_CONFIG_H '-DPACKAGE_VERSION="1.3.2"' "${roots[glue]}"/src/*.c
compile muxclient "${inc[@]}" -DHAVE_CONFIG_H '-DPACKAGE_VERSION="2.1.1"' -I"${roots[libusbmuxd]}/src" "$port/mux_client.c"
compile limd "${inc[@]}" -DHAVE_CONFIG_H '-DPACKAGE_VERSION="1.4.0"' \
    -I"${roots[limd]}/src" "${roots[limd]}"/src/{idevice,service,lockdown}.c \
    "${roots[limd]}"/common/{debug,userpref}.c "$port/property_list_port.c"
mkdir -p "$build/patched"
cp "${roots[limd]}/src/afc.c" "$build/patched/afc.c"
patch --silent "$build/patched/afc.c" < "$repo/tools/iphone/afc.patch"
compile afc "${inc[@]}" -DHAVE_CONFIG_H -I"${roots[limd]}/src" -include "$port/afc_limits.h" \
    "$build/patched/afc.c" "$port/afc_operations.c"
compile daemon "${inc[@]}" -DHAVE_CONFIG_H \
    "${roots[usbmuxd]}"/src/{device,preflight,log,utils}.c \
    "$port/usb_android.c" "$port/mux_server.c" "$port/pair_store.c" "$port/worker.c" "$port/crypto_test.c"
"${compiler[@]}" -pie -Wl,--gc-sections -Wl,-z,relro,-z,now,-z,noexecstack \
    -Wl,-z,max-page-size=16384,-z,common-page-size=16384 \
    "${objects[@]}" -llog -lm -o "$build/jniLibs/arm64-v8a/libiphone-worker.so"
readelf -h "$build/jniLibs/arm64-v8a/libiphone-worker.so" | sed -n '/Class:/p; /Machine:/p; /Type:/p'
