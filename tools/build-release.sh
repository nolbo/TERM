#!/usr/bin/env bash
# Sign locally without placing passwords in arguments or repository files.
set +x
set -euo pipefail

terminal_repo_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
cd -- "$terminal_repo_dir"

export TERMINAL_SIGNING_STORE_FILE=${TERMINAL_SIGNING_STORE_FILE:-"$HOME/.local/share/terminal-signing/terminal-release.p12"}
if [[ ! -f "$TERMINAL_SIGNING_STORE_FILE" ]]; then
    echo 'Release keystore not found. Set TERMINAL_SIGNING_STORE_FILE to your existing keystore.' >&2
    exit 1
fi

if [[ -z ${TERMINAL_SIGNING_STORE_PASSWORD:-} ]]; then
    if [[ ! -t 0 ]]; then
        echo 'Set TERMINAL_SIGNING_STORE_PASSWORD for a non-interactive release build.' >&2
        exit 1
    fi
    read -r -s -p 'Keystore password: ' TERMINAL_SIGNING_STORE_PASSWORD
    printf '\n'
fi
if [[ -z "$TERMINAL_SIGNING_STORE_PASSWORD" ]]; then
    echo 'The keystore password must not be empty.' >&2
    exit 1
fi

export TERMINAL_SIGNING_STORE_PASSWORD
export TERMINAL_SIGNING_KEY_PASSWORD=${TERMINAL_SIGNING_KEY_PASSWORD:-"$TERMINAL_SIGNING_STORE_PASSWORD"}
export TERMINAL_SIGNING_KEY_ALIAS=${TERMINAL_SIGNING_KEY_ALIAS:-terminal}
trap 'unset TERMINAL_SIGNING_STORE_PASSWORD TERMINAL_SIGNING_KEY_PASSWORD' EXIT

./gradlew :android-app:assembleRelease --console=plain
