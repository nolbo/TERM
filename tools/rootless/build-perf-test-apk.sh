#!/usr/bin/env bash
# Package development payloads separately so testing does not stop live sessions.
set -euo pipefail
repository_root=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
payload_directory=${1:?usage: build-perf-test-apk.sh PAYLOAD_DIRECTORY OUTPUT_APK [BASELINE_PROOT]}
output_apk=${2:?output APK is required}
baseline=${3:-}
sdk_root=${PROOT_TEST_SDK_ROOT:-"$repository_root/.android-sdk"}
signer="$sdk_root/build-tools/36.0.0/apksigner"
keystore=${PROOT_TEST_KEYSTORE:-"$HOME/.android/debug.keystore"}
[[ -f "$keystore" ]] || { echo "Supply a debug keystore with PROOT_TEST_KEYSTORE" >&2; exit 1; }
build_directory=$(mktemp -d /tmp/terminal-proot-perf-apk.XXXXXX)
trap 'rm -rf -- "$build_directory"' EXIT
cat > "$build_directory/AndroidManifest.xml" <<'XML'
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="dev.terminal.prootperftest" android:versionCode="1" android:versionName="1">
    <uses-sdk android:minSdkVersion="31" android:targetSdkVersion="35"/>
    <application android:hasCode="false" android:debuggable="true" android:extractNativeLibs="true" android:label="PRoot performance test"/>
</manifest>
XML
"$repository_root/tools/aapt2" link --manifest "$build_directory/AndroidManifest.xml" \
    -I "$sdk_root/platforms/android-35/android.jar" -o "$build_directory/unsigned.apk"
python3 - "$build_directory/unsigned.apk" "$payload_directory" "$baseline" "${PROOT_TEST_NOTIFY_BINARY:-}" "${PROOT_TEST_FASTSTAT_DIRECTORY:-}" "${PROOT_TEST_IO_BINARY:-}" <<'PY'
import pathlib
import sys
import zipfile
with zipfile.ZipFile(sys.argv[1], "a", compression=zipfile.ZIP_DEFLATED) as archive:
    for name in ("proot", "proot-loader"):
        archive.write(pathlib.Path(sys.argv[2]) / name, "lib/arm64-v8a/lib" + name + ".so")
    if sys.argv[3]:
        archive.write(sys.argv[3], "lib/arm64-v8a/libproot-baseline.so")
    if sys.argv[4]:
        archive.write(sys.argv[4], "lib/arm64-v8a/libnotify-open-test.so")
    if sys.argv[6]:
        archive.write(sys.argv[6], "lib/arm64-v8a/libsequential-io-test.so")
    if sys.argv[5]:
        fixtures = pathlib.Path(sys.argv[5])
        for source, target in (("libproot-faststat.so", "libproot-faststat.so"),
                               ("faststat-test", "libfaststat-test.so"),
                               ("trampoline-test", "libtrampoline-test.so"),
                               ("fd-test", "libfd-test.so")):
            archive.write(fixtures / source, "lib/arm64-v8a/" + target)
PY
"$signer" sign --ks "$keystore" --ks-pass pass:android \
    --out "$output_apk" "$build_directory/unsigned.apk"
"$signer" verify "$output_apk"
