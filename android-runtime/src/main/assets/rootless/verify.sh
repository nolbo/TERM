set -eu
export PATH=/usr/sbin:/usr/bin:/sbin:/bin
family=$1
shift
echo TERMINAL_SETUP_PHASE:VERIFY_PACKAGES
if [ "$family" = archlinux ]; then
    pacman -Q "$@"
    pacman -Dk
    pacman --version
else
    test -s /var/lib/terminal-environment/selected-packages
    while IFS= read -r package; do
        [ "$(dpkg-query -W -f='${db:Status-Status}' "$package")" = installed ] || { echo "Package is not configured: $package"; exit 1; }
    done < /var/lib/terminal-environment/selected-packages
    [ -z "$(dpkg --audit)" ]
    apt-get check
    apt-get --version
    less --version
    man --version
fi
# Only exercise commands that belong to this distribution's default selection.
echo TERMINAL_SETUP_PHASE:VERIFY_COMMANDS
bash --noprofile --norc -c 'printf "%s\n" "shell execution OK"'
ps --version
ip -V
ping -V
echo TERMINAL_SETUP_PHASE:VERIFY_LOCALE
test -r /root/.bashrc
test -r /etc/localtime
locale -a | grep -qi '^en_US\.utf8$'
[ "$(LC_ALL=en_US.UTF-8 locale charmap)" = UTF-8 ]
