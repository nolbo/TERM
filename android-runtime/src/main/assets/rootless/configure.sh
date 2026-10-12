set -eu
export PATH=/usr/sbin:/usr/bin:/sbin:/bin
family=$1
echo TERMINAL_SETUP_PHASE:LOCALE
# The distribution install hooks generate en_US.UTF-8; retain other selected locales.
if ! grep -qE '^[[:space:]]*en_US\.UTF-8[[:space:]]+UTF-8' /etc/locale.gen; then
    printf '\nen_US.UTF-8 UTF-8\n' >> /etc/locale.gen
fi
locale-gen
if [ "$family" = archlinux ]; then config=/etc/locale.conf; else config=/etc/default/locale; fi
mkdir -p /etc/default
# Preserve a user-selected locale. Replace only the unconfigured bootstrap C/POSIX default.
current=$(sed -n 's/^[[:space:]]*LANG=//p' "$config" 2>/dev/null | tr -d "\"' " || true)
case "$current" in ''|C|POSIX|C.UTF-8|C.utf8)
    contents=$(sed '/^[[:space:]]*LANG=/d' "$config" 2>/dev/null || true)
    printf '%s\nLANG=en_US.UTF-8\n' "$contents" > "$config"
    ;;
esac
# Keep the image/user timezone. Arch's unconfigured timezone uses an explicit UTC fallback.
echo TERMINAL_SETUP_PHASE:TIMEZONE
if [ ! -e /etc/localtime ] && [ ! -L /etc/localtime ]; then
    ln -s ../usr/share/zoneinfo/Etc/UTC /etc/localtime
fi
