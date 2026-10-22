#!/bin/sh
# Executed only inside an isolated maintenance VM. vde is a COPY, never the user's state disk.
set -eu
export PATH=/usr/sbin:/usr/bin:/sbin:/bin
case " $(cat /proc/cmdline) " in
  *' term.maintenance=components '*) operation=components;;
  *' term.maintenance=rebase '*) operation=rebase;;
  *) echo 'Not a host-selected maintenance boot' >&2; exit 1;;
esac
mkdir -p /run/term-maintenance/lower /run/term-maintenance/state /run/term-maintenance/old
cleanup() {
  sync
  umount -R /run/term-maintenance/old 2>/dev/null || true
  umount /run/term-maintenance/state 2>/dev/null || true
  umount /run/term-maintenance/lower 2>/dev/null || true
}
trap cleanup EXIT
mount -t ext4 -o ro,noload /dev/vdd /run/term-maintenance/lower
mount -t ext4 -o rw /dev/vde /run/term-maintenance/state
case " $(cat /proc/cmdline) " in
  *' term.source-format=2 '*)
    mount --bind /run/term-maintenance/state /run/term-maintenance/old
    ;;
  *' term.source-format=1 '*)
    # Only legacy source copies use overlay. The candidate always boots ext4.
    test -d /run/term-maintenance/state/upper
    mkdir -p /run/term-maintenance/state/work
    mount -t overlay -o lowerdir=/run/term-maintenance/lower,upperdir=/run/term-maintenance/state/upper,workdir=/run/term-maintenance/state/work overlay /run/term-maintenance/old
    ;;
  *) echo 'Missing or unsupported source disk format' >&2; exit 1;;
esac
mount --rbind /dev /run/term-maintenance/old/dev
mount --make-rslave /run/term-maintenance/old/dev
mount -t proc proc /run/term-maintenance/old/proc
python3 /usr/local/lib/droid/migrate-avf-state.py "$operation"
cleanup
trap - EXIT
mkdir -p /var/lib/droid/maintenance
date +%s > /var/lib/droid/maintenance/complete
sync
