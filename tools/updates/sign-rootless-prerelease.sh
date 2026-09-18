#!/usr/bin/env bash
# Sign a reviewed, locally built ARM64 candidate with the existing CI key.
set +x
set -euo pipefail
umask 077
terminal_sign_dir=$(mktemp -d)
trap 'rm -rf -- "$terminal_sign_dir"' EXIT
terminal_tag=${GITHUB_REF_NAME:?}
[[ $terminal_tag =~ ^v0\.1\.0-rootless\.[0-9]+$ ]]
terminal_code=$((1000000 + GITHUB_RUN_NUMBER))
cd "$terminal_sign_dir"
test "$(gh release view "$terminal_tag" --repo nolbo/TERM --json isDraft --jq .isDraft)" = true
for attempt in $(seq 1 120); do
    if gh release download "$terminal_tag" --repo nolbo/TERM --pattern signing-input.tar.gz --clobber 2>/dev/null; then break; fi
    sleep 10
done
test -f signing-input.tar.gz
python3 - <<'PY'
import tarfile, shutil
with tarfile.open('signing-input.tar.gz', 'r:gz') as archive:
    members = archive.getmembers()
    assert sorted(m.name for m in members) == ['request.json', 'unsigned.apk']
    for member in members:
        assert member.isfile()
        assert 0 < member.size <= (512 * 1024 * 1024 if member.name == 'unsigned.apk' else 4096)
        with archive.extractfile(member) as source, open(member.name, 'xb') as output:
            shutil.copyfileobj(source, output)
PY
test "$(jq -r .commit request.json)" = "$GITHUB_SHA"
test "$(jq -r .versionCode request.json)" = "$terminal_code"
test "$(jq -r .tag request.json)" = "$terminal_tag"
test "$(sha256sum unsigned.apk | cut -d ' ' -f 1)" = "$(jq -r .apkSha256 request.json)"
terminal_build_tools=$(find "$ANDROID_HOME/build-tools" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -1)
"$terminal_build_tools/aapt" dump badging unsigned.apk > badging.txt
head -1 badging.txt > package.txt
grep -F "name='dev.terminal.app'" package.txt
grep -F "versionCode='$terminal_code'" package.txt
grep -F "versionName='${terminal_tag#v}+dev.$terminal_code.${GITHUB_SHA:0:12}'" package.txt
"$terminal_build_tools/zipalign" -c -P 16 4 unsigned.apk
printf '%s' "$TERMINAL_SIGNING_KEYSTORE_BASE64" | base64 --decode > release.p12
unset TERMINAL_SIGNING_KEYSTORE_BASE64
terminal_apk="TERM-${terminal_tag}-arm64.apk"
"$terminal_build_tools/apksigner" sign --ks release.p12 --ks-key-alias terminal \
    --ks-pass env:TERMINAL_SIGNING_STORE_PASSWORD --key-pass env:TERMINAL_SIGNING_STORE_PASSWORD \
    --out "$terminal_apk" unsigned.apk
"$terminal_build_tools/apksigner" verify --verbose --print-certs "$terminal_apk" > signing-verification.txt
sha256sum "$terminal_apk" > SHA256SUMS
gh release upload "$terminal_tag" --repo nolbo/TERM "$terminal_apk" SHA256SUMS signing-verification.txt --clobber
# The agent verifies and publishes the draft after this job succeeds.
gh release delete-asset "$terminal_tag" signing-input.tar.gz --repo nolbo/TERM --yes
