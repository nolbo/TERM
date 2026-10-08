#!/bin/sh
# Image finalization: retain repository trust/configuration and installed dpkg state.
set -eu
root=$(cd "${1:?usage: clean-avf-apt.sh ROOTFS}" && pwd -P)
root=${root%/}
# Reject upstream links before changing anything; no path may escape ROOTFS.
for relative in var var/lib var/lib/apt var/lib/apt/lists var/cache var/cache/apt var/cache/apt/archives; do
  if [ -L "$root/$relative" ]; then
    echo "APT cache path is a symlink: $relative" >&2
    exit 1
  fi
done
for relative in var/lib/apt/lists var/cache/apt/archives; do
  directory="$root/$relative"
  [ -d "$directory" ] || continue
  for entry in "$directory"/* "$directory"/.[!.]* "$directory"/..?*; do
    [ -e "$entry" ] || [ -L "$entry" ] || continue
    name=${entry##*/}
    if [ ! -L "$entry" ]; then
      case "$name" in
        lock) continue ;;
        partial|auxfiles)
          if [ -d "$entry" ]; then
            find "$entry" -mindepth 1 -maxdepth 1 -exec rm -rf -- {} +
            continue
          fi ;;
        *_Release|*_Release.gpg|*_InRelease)
          if [ "$relative" = var/lib/apt/lists ] && [ -f "$entry" ]; then continue; fi ;;
      esac
    fi
    rm -rf -- "$entry"
  done
done
rm -f -- "$root/var/cache/apt/pkgcache.bin" "$root/var/cache/apt/srcpkgcache.bin"
