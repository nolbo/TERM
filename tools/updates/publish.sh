#!/usr/bin/env bash
# Publish only to TERM's distribution repository. Never replace a published asset.
set +x
set -euo pipefail
terminal_update_dir=${1:?Pass the signed update artifact directory}
if [[ -z ${GH_TOKEN:-} ]]; then
    echo 'Register TERMINAL_UPDATES_TOKEN with Contents: write access to nolbo/TERM.' >&2
    exit 1
fi
terminal_update_commit=$(jq -er '.commit' "$terminal_update_dir/update.json")
terminal_update_code=$(jq -er '.versionCode' "$terminal_update_dir/update.json")
terminal_update_name=$(jq -er '.versionName' "$terminal_update_dir/update.json")
[[ $terminal_update_commit =~ ^[0-9a-f]{40}$ && $terminal_update_code =~ ^[0-9]+$ ]]
terminal_release_tag="main-$terminal_update_commit"
terminal_publish_dir=$(mktemp -d)
trap 'rm -f "$terminal_publish_dir/latest.json" "$terminal_publish_dir/latest-error.txt"; rmdir "$terminal_publish_dir"' EXIT

# Serialized by the workflow; a slow older build must not become the latest update.
terminal_latest_code=0
if gh api repos/nolbo/TERM/releases/latest > "$terminal_publish_dir/latest.json" 2> "$terminal_publish_dir/latest-error.txt"; then
    terminal_latest_name=$(jq -er '.name' "$terminal_publish_dir/latest.json")
    if [[ $terminal_latest_name =~ \+main\.([0-9]+)\. ]]; then
        terminal_latest_code=${BASH_REMATCH[1]}
    else
        echo 'Latest release is not a recognized mainline update; refusing to replace it.' >&2
        exit 1
    fi
else
    terminal_latest_error=$(<"$terminal_publish_dir/latest-error.txt")
    if [[ $terminal_latest_error != *"(HTTP 404)"* ]]; then
        echo 'Could not safely read the latest distribution release.' >&2
        exit 1
    fi
fi

if terminal_is_draft=$(gh release view "$terminal_release_tag" --repo nolbo/TERM --json isDraft --jq '.isDraft' 2>/dev/null); then
    if [[ $terminal_is_draft == false ]]; then
        echo 'This mainline update has already been published; assets were preserved.'
        exit 0
    fi
    gh release upload "$terminal_release_tag" --repo nolbo/TERM "$terminal_update_dir"/* --clobber
else
    gh release create "$terminal_release_tag" --repo nolbo/TERM --draft \
        --title "$terminal_update_name" \
        --notes "TERM mainline update. Android installation requires user confirmation. Save live terminal work before installing." \
        "$terminal_update_dir"/*
fi
terminal_mark_latest=false
if (( terminal_update_code > terminal_latest_code )); then terminal_mark_latest=true; fi
gh release edit "$terminal_release_tag" --repo nolbo/TERM --draft=false --latest="$terminal_mark_latest"
