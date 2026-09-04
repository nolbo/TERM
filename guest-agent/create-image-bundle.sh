#!/bin/sh
set -eu

if [ "$#" -lt 4 ]; then
    echo "usage: $0 OUTPUT.tar.gz ARCHITECTURE VM_CONFIG IMAGE_FILE..." >&2
    exit 2
fi

output=$1
architecture=$2
config=$3
shift 3
case "$architecture" in
    arm64|x86_64) ;;
    *) echo "architecture must be arm64 or x86_64" >&2; exit 2 ;;
esac

for file in "$config" "$@"; do
    if [ ! -f "$file" ]; then
        echo "missing image file: $file" >&2
        exit 2
    fi
done

work=$(mktemp -d "${TMPDIR:-/tmp}/xtermx-image.XXXXXX")
trap 'rm -rf "$work"' EXIT HUP INT TERM
cp "$config" "$work/vm_config.json"
for file in "$@"; do
    name=$(basename "$file")
    case "$name" in
        manifest.json|vm_config.json) echo "reserved image file name: $name" >&2; exit 2 ;;
    esac
    cp -- "$file" "$work/$name"
done

python3 - "$work" "$architecture" <<'PY'
import hashlib, json, os, sys
root, architecture = sys.argv[1:]
files = {}
for name in sorted(os.listdir(root)):
    path = os.path.join(root, name)
    if not os.path.isfile(path) or os.path.islink(path):
        raise SystemExit("bundle inputs must be regular files")
    digest = hashlib.sha256()
    with open(path, "rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    files[name] = digest.hexdigest()
manifest = {
    "format_version": 1,
    "guest_protocol_version": 2,
    "architecture": architecture,
    "files": files,
}
with open(os.path.join(root, "manifest.json"), "w", encoding="utf-8") as target:
    json.dump(manifest, target, sort_keys=True, separators=(",", ":"))
    target.write("\n")
PY

mkdir -p "$(dirname -- "$output")"
tar --format=ustar --sort=name --owner=0 --group=0 --numeric-owner \
    -czf "$output" -C "$work" .
sha256sum "$output"
