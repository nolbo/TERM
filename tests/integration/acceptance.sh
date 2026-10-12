#!/bin/sh
set -eu

# Execute on a privileged AOSP/ARM64 test device after provisioning a VM image.
# The script is intentionally an operator checklist: normal CI cannot grant
# signature AVF permissions or observe a guest that survives Android Recents.
adb shell am start -n dev.terminal.app/.MainActivity
echo 'Manual acceptance sequence:'
echo '1. 초기 설정에서 서명된 자동 이미지 또는 검증된 로컬 이미지를 선택한다.'
echo '2. 콘솔에서 VM 시작, 새 셸을 누르고: while true; do date; sleep 5; done'
echo '3. Activity를 종료/재생성하고 같은 세션 탭을 눌러 출력 지속을 확인한다.'
echo '4. droid clipboard set hello; droid clipboard get; droid notify --title test --text hello'
echo '5. echo ok >/mnt/shared/test.txt 후 Android 공유 디렉터리를 확인한다.'
echo '6. python -m http.server 8000 후 설정 > 진단에서 전달 포트와 127.0.0.1:8000을 확인한다.'
echo '7. 최근 앱에서 태스크를 제거하고 sleep 600이 선택한 절전 정책에서 유지되는지 확인한다.'
