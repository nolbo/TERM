set -eu
# Package operations can replace resolv.conf with a systemd-resolved stub, but no resolver
# daemon runs in PRoot. Restore the host-selected configuration even after an interrupted retry.
state=/var/lib/terminal-environment
mkdir -p "$state"
if [ -e "$state/resolv.conf" ] || [ -L "$state/resolv.conf" ]; then
    rm -f /etc/resolv.conf
    cp -a "$state/resolv.conf" /etc/resolv.conf
else
    cp -a /etc/resolv.conf "$state/resolv.conf"
fi
export PATH=/usr/sbin:/usr/bin:/sbin:/bin
# Keep service startup disabled in this userspace; preserve an existing administrator policy.
policy=/usr/sbin/policy-rc.d
created_policy=false
if [ -f "$state/policy-created" ]; then created_policy=true; fi
if [ ! -e "$policy" ] && [ ! -L "$policy" ]; then
    : > "$state/policy-created"
    printf '#!/bin/sh\nexit 101\n' > "$policy"
    chmod 755 "$policy"
    created_policy=true
fi
cleanup() {
    if [ "$created_policy" = true ]; then rm -f "$policy" "$state/policy-created"; fi
    rm -f /etc/resolv.conf
    cp -a "$state/resolv.conf" /etc/resolv.conf
    rm -f "$state/resolv.conf"
}
trap cleanup EXIT HUP INT TERM
apt() {
    apt-get -y --no-remove --install-recommends \
        -o Dpkg::Options::=--force-confold -o DPkg::Lock::Timeout=60 \
        -o APT::Status-Fd=1 -o Dpkg::Progress-Fancy=0 \
        -o Acquire::Retries=2 -o Acquire::http::Timeout=30 -o Acquire::https::Timeout=30 "$@"
}
# A failed index refresh must not silently proceed with stale indexes.
echo TERMINAL_SETUP_PHASE:PACKAGE_INDEX
apt -o APT::Update::Error-Mode=any update
echo TERMINAL_SETUP_PHASE:PACKAGE_RECOVERY
dpkg --force-confold --configure -a || apt -f install
# Save the concrete distribution selection, not apt patterns, for offline validation.
echo TERMINAL_SETUP_PHASE:PACKAGE_PLAN
apt-cache pkgnames >/dev/null
command apt list "$@" 2>/dev/null | sed '1d; s,/.*,,; /^[a-z0-9][a-z0-9+.-]*$/!d' > "$state/selected-packages.pending"
test -s "$state/selected-packages.pending"
echo TERMINAL_SETUP_PHASE:PACKAGE_INSTALL
apt install "$@"
echo TERMINAL_SETUP_PHASE:PACKAGE_UPGRADE
apt --with-new-pkgs upgrade
echo TERMINAL_SETUP_PHASE:PACKAGE_CHECK
apt check
mv "$state/selected-packages.pending" "$state/selected-packages"
