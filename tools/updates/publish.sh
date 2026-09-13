#!/usr/bin/env bash
# Immutable APK releases; only the dev-latest discovery manifest is mutable.
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
terminal_update_channel=$(jq -er '.channel // "main"' "$terminal_update_dir/update.json")
[[ $terminal_update_channel == main || $terminal_update_channel == dev ]]
terminal_release_tag="$terminal_update_channel-$terminal_update_commit"
terminal_latest_endpoint=repos/nolbo/TERM/releases/latest
terminal_prerelease=false
if [[ $terminal_update_channel == dev ]]; then
    terminal_latest_endpoint=repos/nolbo/TERM/releases/tags/dev-latest
    terminal_prerelease=true
fi
terminal_publish_dir=$(mktemp -d)
trap 'rm -f "$terminal_publish_dir/latest.json" "$terminal_publish_dir/latest-error.txt"; rmdir "$terminal_publish_dir"' EXIT

# Serialized by the workflow; a slow older build must not become the latest update.
terminal_latest_code=0
terminal_pointer_exists=false
terminal_pointer_draft=false
if gh api "$terminal_latest_endpoint" > "$terminal_publish_dir/latest.json" 2> "$terminal_publish_dir/latest-error.txt"; then
    terminal_pointer_exists=true
    terminal_pointer_draft=$(jq -r '.draft // false' "$terminal_publish_dir/latest.json")
    terminal_latest_name=$(jq -er '.name' "$terminal_publish_dir/latest.json")
    if [[ $terminal_latest_name =~ \+$terminal_update_channel\.([0-9]+)\. ]]; then
        terminal_latest_code=${BASH_REMATCH[1]}
    else
        echo 'Latest release is not a recognized channel update; refusing to replace it.' >&2
        exit 1
    fi
else
    terminal_latest_error=$(<"$terminal_publish_dir/latest-error.txt")
    if [[ $terminal_latest_error != *"(HTTP 404)"* ]]; then
        echo 'Could not safely read the latest distribution release.' >&2
        exit 1
    fi
fi

terminal_already_published=false
if terminal_is_draft=$(gh release view "$terminal_release_tag" --repo nolbo/TERM --json isDraft --jq '.isDraft' 2>/dev/null); then
    if [[ $terminal_is_draft == false ]]; then
        echo 'This channel update has already been published; assets were preserved.'
        terminal_already_published=true
        if [[ $terminal_update_channel == main ]]; then exit 0; fi
    else
        gh release upload "$terminal_release_tag" --repo nolbo/TERM "$terminal_update_dir"/* --clobber
    fi
else
    gh release create "$terminal_release_tag" --repo nolbo/TERM --draft \
        --prerelease="$terminal_prerelease" --latest=false \
        --title "$terminal_update_name" \
        --notes "TERM $terminal_update_channel update. Android installation requires user confirmation. Save live terminal work before installing." \
        "$terminal_update_dir"/*
fi
terminal_mark_latest=false
if [[ $terminal_update_channel == main ]] && (( terminal_update_code > terminal_latest_code )); then terminal_mark_latest=true; fi
if [[ $terminal_already_published == false ]]; then
    gh release edit "$terminal_release_tag" --repo nolbo/TERM --draft=false \
        --prerelease="$terminal_prerelease" --latest="$terminal_mark_latest"
fi

# GitHub's /latest excludes prereleases. Keep a dedicated, prerelease discovery
# pointer, containing metadata only; every APK URL still uses its immutable tag.
if [[ $terminal_update_channel == dev ]] && { (( terminal_update_code > terminal_latest_code )) || { (( terminal_update_code == terminal_latest_code )) && [[ $terminal_pointer_draft == true ]]; }; }; then
    if [[ $terminal_pointer_exists == true ]]; then
        gh release upload dev-latest --repo nolbo/TERM "$terminal_update_dir/update.json" --clobber
    else
        gh release create dev-latest --repo nolbo/TERM --draft --prerelease --latest=false \
            --title "$terminal_update_name" --notes 'Development channel discovery metadata. APK assets are in commit-specific prereleases.' \
            "$terminal_update_dir/update.json"
    fi
    gh release edit dev-latest --repo nolbo/TERM --draft=false --prerelease \
        --latest=false --title "$terminal_update_name"
fi
