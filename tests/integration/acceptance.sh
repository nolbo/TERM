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
echo '5. virtiofsVersion=1 이미지를 사용해 설정 > 공유 디렉토리에서 폴더를 AVF에 연결하고 VM을 중지/시작한다. /mnt/term-shares/<name>에서 양방향 읽기/쓰기와 64 MiB 초과 파일을 확인한다. 연결하지 않은 폴더와 호스트 앱 데이터는 보이지 않아야 한다. 두 VM의 연결 범위, 64자 이름, 심볼릭 링크의 호스트 경로 탈출 거부를 확인한다.'
echo '5a. 이름 변경·연결 해제와 Activity 재생성은 실행 중 마운트를 바꾸지 않아야 한다. 연결 해제 후에도 실행 중 VM이 있으면 폴더 데이터 삭제가 거부되어야 한다. VM 중지/시작 후 새 연결만 반영되고 다른 VM·원본 데이터를 보존하는지 확인한다. VM 종료/부팅 실패 후 해당 virtiofs 프로세스·소켓 정리와 호스트 프로세스 재시작 후 삭제 보호도 확인한다.'
echo '5b. 별도의 파일 가져오기/내보내기로 SHA-256·원본 보존·경로 탈출·취소를 확인한다. 사본은 /home/droid/term-transfers/<name>/<path>이며 복사의 64 MiB·2분 제한은 직접 공유에 적용되지 않는다. 이전 이미지의 파일 복사와 Native·Userspace Linux 접근이 유지되어야 한다.'
echo '6. python -m http.server 8000 후 설정 > 진단에서 전달 포트와 127.0.0.1:8000을 확인한다.'
echo '7. 최근 앱에서 태스크를 제거하고 sleep 600이 선택한 절전 정책에서 유지되는지 확인한다.'
