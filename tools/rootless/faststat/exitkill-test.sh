#!/system/bin/sh
# Run as the app UID: sh exitkill-test.sh PROOT LOADER ROOTFS HOST_TMP
# Only processes and files created by this test are signalled/removed.
set -eu
proot=$1
loader=$2
rootfs=$3
host_tmp=$4
fixture=$(mktemp -d "$rootfs/tmp/terminal-exitkill-XXXXXX")
guest_fixture=/tmp/${fixture##*/}
tracer=
pids=
cleanup() {
    [ -z "$tracer" ] || kill -9 "$tracer" 2>/dev/null || true
    for pid in $pids; do kill -9 "$pid" 2>/dev/null || true; done
    [ -z "$tracer" ] || wait "$tracer" 2>/dev/null || true
    rm -rf "$fixture"
}
trap cleanup EXIT HUP INT TERM
alive() {
    [ -r "/proc/$1/stat" ] || return 1
    state=$(awk '{print $3}' "/proc/$1/stat" 2>/dev/null) || return 1
    [ "$state" != Z ] && [ "$state" != X ]
}
for mode in 0 1; do
    rm -f "$fixture/pids"
    PROOT_EXITKILL=$mode PROOT_LOADER="$loader" PROOT_TMP_DIR="$host_tmp" \
        "$proot" -0 -r "$rootfs" -b /dev -b /proc -w /tmp \
        /usr/bin/env -i PATH=/usr/bin:/bin HOME=/root \
        /bin/sh -c 'echo $$ > "$1/pids"; sleep 60 & echo $! >> "$1/pids"; wait' \
        test "$guest_fixture" >/dev/null 2>&1 &
    tracer=$!
    attempt=0
    while [ ! -f "$fixture/pids" ] || [ "$(wc -l < "$fixture/pids")" -ne 2 ]; do
        attempt=$((attempt + 1))
        [ "$attempt" -lt 100 ] || { echo 'FAIL tracees not ready'; exit 1; }
        sleep .05
    done
    pids=$(cat "$fixture/pids")
    for pid in $pids; do alive "$pid" || { echo 'FAIL tracee already dead'; exit 1; }; done
    kill -9 "$tracer"
    wait "$tracer" 2>/dev/null || true
    tracer=
    sleep .2
    for pid in $pids; do
        if [ "$mode" = 1 ]; then
            attempt=0
            while alive "$pid"; do
                attempt=$((attempt + 1))
                [ "$attempt" -lt 100 ] || { echo 'FAIL surviving tracee'; exit 1; }
                sleep .05
            done
        else
            alive "$pid" || { echo 'FAIL baseline did not survive'; exit 1; }
            kill -9 "$pid"
        fi
    done
    pids=
    echo "PASS exitkill=$mode: tracer death checked for shell and child"
done
rm -f "$fixture/pids"
PROOT_EXITKILL=1 PROOT_LOADER="$loader" PROOT_TMP_DIR="$host_tmp" \
    "$proot" --kill-on-exit -0 -r "$rootfs" -b /dev -b /proc -w /tmp \
    /usr/bin/env -i PATH=/usr/bin:/bin HOME=/root \
    /bin/sh -c 'sleep 60 & echo $! > "$1/pids"; exit 0' \
    test "$guest_fixture" >/dev/null 2>&1 &
tracer=$!
wait "$tracer"
tracer=
pids=$(cat "$fixture/pids")
for pid in $pids; do
    alive "$pid" && { echo 'FAIL child survived normal exit'; exit 1; }
done
pids=
echo 'PASS normal command exit reaps child'
