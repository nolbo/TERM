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

echo '8. androidStorageVersion=1 비보호 AVF 이미지와 기존 공통 모든 파일 접근 권한으로 다음 VM 부팅의 /sdcard 접근을 확인한다. Download에서 Native·Userspace Linux·AVF 양방향 읽기/쓰기, 두 AVF VM 동시 공유와 Android/data 보호 경로 차단을 확인한다. 실행 중 권한 허용은 마운트를 추가하지 않아야 한다. 권한 해제 시 내부저장소 공유 VM만 중지하고 폴더 전용 VM은 유지해야 한다. 열려 있던 파일 접근·부팅 중 해제·호스트 프로세스 종료 직후 해제와 재시작 복구·실패한 중지의 기록 유지도 확인한다. 이전 이미지·보호 VM·권한 거부는 /sdcard를 제공하지 않아야 하며 기존 공유 폴더와 사본 전송은 유지되어야 한다.'
echo '8a. 실행환경 상세의 권한 > 내부저장소 접근과 새 실행환경 생성의 권한 선택을 확인한다. 새 Userspace Linux·AVF 환경은 기본 꺼짐이고 이전 메타데이터는 기존 동작을 유지해야 한다. 선택과 Android 공통 권한이 모두 켜져야 새 세션/다음 VM 부팅에 연결된다. 환경 선택을 꺼도 기존 마운트는 중지 전까지 유지되어야 하고 다른 환경의 선택·Android OS 권한을 변경하지 않아야 한다. Native는 실제 공통 권한 상태와 설정 이동만 표시한다. 생성 초안의 취소·재생성·기존 환경 선택은 저장된 환경을 변경하지 않아야 한다.'
