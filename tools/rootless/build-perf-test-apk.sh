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
python3 - "$build_directory/unsigned.apk" "$payload_directory" "$baseline" "${PROOT_TEST_NOTIFY_BINARY:-}" "${PROOT_TEST_FASTSTAT_DIRECTORY:-}" "${PROOT_TEST_IO_BINARY:-}" "${PROOT_TEST_STDIO_DIRECTORY:-}" "${PROOT_TEST_BASELINE_FASTSTAT:-}" "${PROOT_TEST_POLICY_BINARY:-}" "${PROOT_TEST_WORKLOAD_DIRECTORY:-}" "${PROOT_TEST_WORKLOAD_TOOLS:-}" <<'PY'
import pathlib
import sys
import zipfile
import hashlib
import json
with zipfile.ZipFile(sys.argv[1], "a", compression=zipfile.ZIP_DEFLATED) as archive:
    for name in ("proot", "proot-loader"):
        archive.write(pathlib.Path(sys.argv[2]) / name, "lib/arm64-v8a/lib" + name + ".so")
    if sys.argv[3]:
        archive.write(sys.argv[3], "lib/arm64-v8a/libproot-baseline.so")
    if sys.argv[8]:
        archive.write(sys.argv[8], "lib/arm64-v8a/libfaststat-baseline.so")
    if sys.argv[9]:
        archive.write(sys.argv[9], "lib/arm64-v8a/libandroid-policy-test.so")
    if sys.argv[4]:
        archive.write(sys.argv[4], "lib/arm64-v8a/libnotify-open-test.so")
    if sys.argv[7]:
        fixtures = pathlib.Path(sys.argv[7])
        for source, target in (("stdio-buffer.so", "libstdio-buffer.so"),
                               ("stdio-test", "libstdio-test.so"),
                               ("stdio-probe", "libstdio-probe.so")):
            archive.write(fixtures / source, "lib/arm64-v8a/" + target)
        for source, target in (("stdio-counted.so", "libstdio-counted.so"),
                               ("stdio-pool-test", "libstdio-pool-test.so"),
                               ("stdio-close-gate.so", "libstdio-close-gate.so"),
                               ("stdio-lifetime-test", "libstdio-lifetime-test.so"),
                               ("stdio-small-probe", "libstdio-small-probe.so"),
                               ("stdio-semantics-test", "libstdio-semantics-test.so")):
            if (fixtures / source).is_file():
                archive.write(fixtures / source, "lib/arm64-v8a/" + target)
    if sys.argv[6]:
        archive.write(sys.argv[6], "lib/arm64-v8a/libsequential-io-test.so")
    if sys.argv[5]:
        fixtures = pathlib.Path(sys.argv[5])
        for source, target in (("libproot-faststat.so", "libproot-faststat.so"),
                               ("faststat-test", "libfaststat-test.so"),
                               ("trampoline-test", "libtrampoline-test.so"),
                               ("fd-test", "libfd-test.so")):
            archive.write(fixtures / source, "lib/arm64-v8a/" + target)
        for name in ("fd-utimens-test", "fd-utimens-test-static", "identity-regression-test",
                     "identity-regression-test-static", "link-mutation-test", "link-mutation-test-static", "rootless-concurrency-test",
                     "rootless-concurrency-test-static", "rootless-fork-test",
                     "rootless-fork-test-static", "pin-fork-test", "pin-fork-test-static", "rootless-restart-test", "rootless-restart-test-static", "stat-entry-test", "open-test", "emulated-fstat-entry-test"):
            if (fixtures / name).is_file():
                archive.write(fixtures / name, "lib/arm64-v8a/lib" + name + ".so")
    if sys.argv[10]:
        workloads = pathlib.Path(sys.argv[10])
        for name in ("common.py", "fixtures.py", "worker.py"):
            archive.write(workloads / name, "assets/rootless-workloads/" + name)
    if sys.argv[11]:
        tools = pathlib.Path(sys.argv[11])
        for path in sorted(tools.rglob("*")):
            if path.is_file() and not path.is_symlink():
                archive.write(path, "assets/rootless-tools/" + path.relative_to(tools).as_posix())
    digests = {name: hashlib.sha256(archive.read(name)).hexdigest()
               for name in archive.namelist() if name.startswith(("lib/arm64-v8a/", "assets/rootless-"))}
    archive.writestr("assets/rootless-runtime-manifest.json", json.dumps({"sha256": digests}, sort_keys=True) + "\n")
PY
"$signer" sign --ks "$keystore" --ks-pass pass:android \
    --out "$output_apk" "$build_directory/unsigned.apk"
"$signer" verify "$output_apk"
