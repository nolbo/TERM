#!/system/bin/sh
# Run under the selected app UID. Never install into its distribution package DB.
set -eu
library=${1:?APK native library directory required}
jobs=${2:?directory containing explicit workload job JSON files required}
output=${3:?new result directory required}
pairs=${4:-10}
runner=${ROOTLESS_TEST_RUNNER:?path to workload-device-run.sh required}
: "${ROOTLESS_TEST_ROOTFS:?explicit existing distribution root required}"
: "${ROOTLESS_TEST_CACHE:?explicit app test cache required}"
: "${ROOTLESS_TEST_WORKLOADS:?prepared workload bundle required}"
case "$pairs" in ''|*[!0-9]*) echo 'pairs must be an integer' >&2; exit 2 ;; esac
[ "$pairs" -ge 1 ] && [ "$pairs" -le 1000 ]
if [ "$pairs" -lt 10 ] && [ "${ROOTLESS_TEST_SMOKE:-0}" != 1 ]; then
    echo 'Timing comparisons require at least 10 pairs; set ROOTLESS_TEST_SMOKE=1 for correctness only' >&2
    exit 2
fi
[ -d "$jobs" ] && [ -f "$ROOTLESS_TEST_WORKLOADS/manifest.json" ]
# mkdir without -p deliberately preserves an existing result directory.
mkdir "$output"
sha256sum "$library/libproot-baseline.so" "$library/libfaststat-baseline.so" \
    "$library/libproot.so" "$library/libproot-loader.so" "$library/libproot-faststat.so" \
    "$ROOTLESS_TEST_WORKLOADS/manifest.json" > "$output/inputs.sha256"
getprop ro.build.version.sdk > "$output/android-api.txt"
getenforce > "$output/selinux.txt" 2>/dev/null || true
pair=1
while [ "$pair" -le "$pairs" ]; do
    if [ "$((pair % 2))" = 1 ]; then order='baseline candidate'; else order='candidate baseline'; fi
    for job in "$jobs"/*.json; do
        [ -f "$job" ] || { echo 'No prepared jobs' >&2; exit 2; }
        name=${job##*/}; name=${name%.json}
        for mode in $order; do
            log="$output/$pair-$name-$mode.log"
            status=0
            ROOTLESS_TEST_JOB="$job" /system/bin/sh "$runner" "$mode" "$library" \
                "${ROOTLESS_TEST_STORAGE:-emulated}" \
                /usr/bin/python3 -B /workloads/worker.py --job /tmp/job.json > "$log" 2>&1 || status=$?
            printf '%s\t%s\t%s\t%s\n' "$pair" "$name" "$mode" "$status" >> "$output/status.tsv"
            printf 'pair=%s case=%s mode=%s exit=%s\n' "$pair" "$name" "$mode" "$status"
        done
    done
    pair=$((pair + 1))
done
printf 'DONE\n' > "$output/done"
