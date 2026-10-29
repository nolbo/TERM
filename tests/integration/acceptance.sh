#!/bin/sh
set -eu

# Operator checklist for a privileged AOSP/ARM64 device with a verified image.
# ReDroid/ordinary CI can test sockets and host ownership, not actual AVF stop.
adb shell am start -n dev.terminal.app/.MainActivity
cat <<'CHECKLIST'
Manual AVF acceptance sequence:
1. 프로필에서 AVF 실행환경을 만들고 승인된 이미지 설치를 완료한다. 기존 환경/디스크는 덮어쓰지 않는다.
2. direct sessiond(40500) 환경 두 개와 Native·Userspace Linux 셸을 연다. 각 셸에서 주기적으로 출력하고 입력 왕복을 확인한다.
3. Activity 재생성·최근 앱 제거 후 같은 살아 있는 세션과 출력이 유지되는지 확인한다. 선택한 절전 정책도 확인한다.
4. VM은 유지한 채 한 환경의 세션 전송만 끊는다. PTY 실행이 계속되고 해당 환경만 재연결되는지, 다른 환경의 입력·종료가 지연되지 않는지 확인한다.
5. 한 VM을 비정상 중지한다. 실제 stopped/absent 증명 후 그 환경의 세션·체크포인트·입력·Workspace만 없어져야 한다. 오류 콜백 뒤 stop 콜백, 일시적인 상태 조회 실패 뒤 재시도도 확인한다.
6. 대량 출력·동시 이미지 표시 중 세션 종료·리사이즈·다른 셸 입력을 실행한다. 종료한 세션이 늦은 출력으로 되살아나지 않고 OutputGap 이후 부분 파서가 초기화되어야 한다.
7. 보조 vsock 연결 실패/지연 중 VM 정지와 다른 채널 조작을 확인한다. 취소된 LIST/ATTACH 재접속은 재시도 가능해야 하며 늦게 반환된 연결은 닫혀야 한다.
8. droid clipboard set hello; droid clipboard get; droid notify --title test --text hello 를 확인한다. python -m http.server 8000 후 AVF 설정의 로컬 서비스/포트 전달을 확인한다.
9. 명시적인 파일 가져오기/내보내기로 SHA-256·원본 보존·경로 탈출·취소를 확인한다. 새 direct 이미지에는 호스트 공유 마운트나 /sdcard 자동 노출을 요구하지 않는다.
10. 기존 sessionResumeVersion=1 이미지의 재연결 호환성을 별도 확인한다. 구성 요소 전환은 검증된 명시적 업데이트 절차로만 수행한다.
11. AVF와 Userspace를 같은 CPU 개수·메모리·작업 조건으로 비교한다. tests/benchmarks/linux_cpu_workload.py를 양쪽에서 실행하고 실기 시작/입력/종료 p50·p95, CPU·RSS·발열을 기록한다. 호스트 소켓 측정으로 대체하지 않는다.
12. 느린 파일시스템의 미디어 표시를 별도 점검한다. 짧은 읽기는 턴마다 양보하지만 개별 파일 시스템 호출의 시간 상한은 보장되지 않는다.
CHECKLIST
