set -eu
export PATH=/usr/sbin:/usr/bin:/sbin:/bin
# Never remove package-manager locks or perform unsupported partial upgrades.
# The approved image already has its keyring and PRoot DisableSandbox policy.
echo TERMINAL_SETUP_PHASE:PACKAGE_INDEX
pacman -Syu --needed --noconfirm --noprogressbar --color never "$@"
