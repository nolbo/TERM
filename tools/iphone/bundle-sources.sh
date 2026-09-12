#!/usr/bin/env bash
# Produce corresponding-source material alongside the APK, without private data.
set -euo pipefail
repo=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
build=$(CDPATH= cd -- "${1:?BUILD_DIRECTORY required}" && pwd)
output=${2:?OUTPUT_ARCHIVE required}
archives=()
while read -r name root digest url; do
    [[ -z "$name" || "$name" == \#* ]] && continue
    printf '%s  %s\n' "$digest" "$build/sources/$name.tar.gz" | sha256sum -c -
    archives+=("sources/$name.tar.gz")
done < "$repo/tools/iphone/sources.lock"
mkdir -p "$(dirname -- "$output")"
tar -czf "$output" -C "$repo" tools/iphone android-runtime/src/main/iphone \
    -C "$build" "${archives[@]}"
