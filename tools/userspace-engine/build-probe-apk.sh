#!/usr/bin/env bash
set -euo pipefail
root=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
output=${1:?usage: build-probe-apk.sh OUTPUT_APK}
sdk=${UE_TEST_SDK_ROOT:-${ANDROID_SDK_ROOT:-"$root/.android-sdk"}}
jar=${UE_TEST_ANDROID_JAR:-"$sdk/platforms/android-37.0/android.jar"}
build_tools=${UE_TEST_BUILD_TOOLS:-"$sdk/build-tools/36.0.0"}
aapt2=${UE_TEST_AAPT2:-"$root/tools/aapt2"}
keystore=${UE_TEST_KEYSTORE:-"$HOME/.android/debug.keystore"}
binary="$root/native/userspace-engine/target/aarch64-linux-android/release/ue-entry-probe"
for required in "$jar" "$build_tools/d8" "$build_tools/apksigner" "$keystore" "$binary"; do
    [[ -f "$required" ]] || { echo "Missing probe build input: $required" >&2; exit 1; }
done
python3 "$root/tools/userspace-engine/check-probe-elf.py" "$binary"
work=$(mktemp -d /tmp/term-ue-probe-apk.XXXXXX)
trap 'rm -rf -- "$work"' EXIT
mkdir "$work/classes" "$work/dex"
javac --release 17 -classpath "$jar" -d "$work/classes" \
    "$root/native/userspace-engine/tests/android/EntryProbe.java"
"$build_tools/d8" --lib "$jar" --min-api 31 --output "$work/dex" \
    "$work/classes/dev/terminal/ueprobe/EntryProbe.class"
"$aapt2" link \
    --manifest "$root/native/userspace-engine/tests/android/AndroidManifest.xml" \
    -I "$jar" -o "$work/unsigned.apk"
python3 - "$work/unsigned.apk" "$work/dex/classes.dex" "$binary" <<'PY'
import hashlib, json, pathlib, sys, zipfile
with zipfile.ZipFile(sys.argv[1], 'a', compression=zipfile.ZIP_DEFLATED) as apk:
    apk.write(sys.argv[2], 'classes.dex')
    apk.write(sys.argv[3], 'lib/arm64-v8a/libue-entry-probe.so')
    apk.writestr('assets/probe.json', json.dumps({
        'kind': 'entry-probe', 'productionEngine': False,
        'sha256': hashlib.sha256(pathlib.Path(sys.argv[3]).read_bytes()).hexdigest()
    }) + '\n')
PY
"$build_tools/apksigner" sign --ks "$keystore" --ks-pass pass:android \
    --out "$output" "$work/unsigned.apk"
"$build_tools/apksigner" verify "$output"
printf '%s\n' "$output"
