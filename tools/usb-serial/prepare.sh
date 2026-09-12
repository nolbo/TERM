#!/usr/bin/env bash
# Pinned upstream Java sources, generated only under the module build directory.
set -euo pipefail
build=${1:?BUILD_DIRECTORY required}
mkdir -p "$build/cache" "$build/java" "$build/assets/licenses/usb-serial"
archive="$build/cache/usb-serial-3.11.0.tar.gz"
digest=9fc12819db958e0e83bc3ec276600758e42e049ec528341c43954768a0793af4
if [[ ! -f "$archive" ]]; then
    curl --fail --location --retry 2 --max-time 90 \
        https://codeload.github.com/mik3y/usb-serial-for-android/tar.gz/refs/tags/3.11.0 -o "$archive.part"
    printf '%s  %s\n' "$digest" "$archive.part" | sha256sum -c -
    mv "$archive.part" "$archive"
fi
printf '%s  %s\n' "$digest" "$archive" | sha256sum -c -
upstream=usb-serial-for-android-3.11.0
tar -xzf "$archive" -C "$build/java" --strip-components=4 \
    "$upstream/usbSerialForAndroid/src/main/java/com/hoho/android/usbserial/driver" \
    "$upstream/usbSerialForAndroid/src/main/java/com/hoho/android/usbserial/util/MonotonicClock.java" \
    "$upstream/usbSerialForAndroid/src/main/java/com/hoho/android/usbserial/util/HexDump.java" \
    "$upstream/usbSerialForAndroid/src/main/java/com/hoho/android/usbserial/util/UsbUtils.java"
# Required host invariant: an approved serial operation must not detach kernel drivers.
# This is a bounded mechanical patch over checksum-pinned generated sources.
claims=$(grep -R -c 'claimInterface(.*true)' "$build/java/java/com/hoho/android/usbserial/driver" | awk -F: '{n+=$2} END {print n}')
[[ "$claims" == 9 ]] || { echo "Unexpected upstream claim sites: $claims" >&2; exit 1; }
sed -i '/claimInterface(/s/, true)/, false)/g' "$build"/java/java/com/hoho/android/usbserial/driver/*.java
# The upstream debug-only baud-rate test hook must never be guest-accessible.
sed -i '/^import com.hoho.android.usbserial.BuildConfig;/d; s/BuildConfig.DEBUG/false/g' \
    "$build"/java/java/com/hoho/android/usbserial/driver/*.java
tar -xOf "$archive" "$upstream/LICENSE.txt" > "$build/assets/licenses/usb-serial/LICENSE.txt"
if grep -R 'claimInterface(.*true)' "$build/java"; then exit 1; fi
