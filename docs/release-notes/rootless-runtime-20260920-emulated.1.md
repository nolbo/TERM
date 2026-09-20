# rootless-runtime-20260920-emulated.1

- 공개일: 2026-09-20
- 대상: ARM64 Rootless PRoot, loader, combined faststat/stdio DSO 및 재현 소스
- 배포 목적: TERM 개발 채널 APK의 해시 고정 빌드 입력. 사용자 앱이 별도로 다운로드하는 실행 파일이 아닙니다.

## 포함된 변경

실제 하드링크 생성을 가속의 필수 조건에서 제거하고 기존 에뮬레이션 형식에서도 일반 파일 조회·열기·생성·닫기를 가속합니다. openat2 의존성을 제거하고, 현재 FD 경로가 확인된 경우 경로 요소별 탐색을 줄입니다. statx, 디렉터리 FD 상대 조회, 파일시스템 확인, 경로 규칙 게시, rename/unlink 처리도 개선했습니다.

조건을 충족하는 단독 FD 테이블에서 fstat 경로 확인을 한 번으로 줄입니다. 신호·FD 공유·비동기 FD 작업·게스트 필터 조건이 바뀌면 해당 단축을 해제합니다. io_uring/seccomp API 자체를 차단하거나 반환값을 바꾸지 않습니다. 공유 ABI는 8이며 엔진과 DSO를 함께 교체해야 합니다. 기존 stdio 버퍼링을 포함합니다.

앱 통합 변경을 포함한 새 APK에서는 기존 패키지와 파일을 변환하지 않고 새 세션에 적용합니다. 새 설치의 하드링크 시험 거부 시 에뮬레이션 형식을 선택합니다. 앱 삭제·배포판 재설치·가속을 위한 새 이전은 필요 없습니다. Android 권한·SELinux·seccomp 정책을 해제하지 않습니다.

## 검증과 제한

- 로컬 canonical 앱 빌드/runtime/image 검증 성공. 최종 단위 검사는 UP-TO-DATE 재사용입니다.
- 업데이트/런타임 입력 Python 검사 19개, APK payload 해시, 재현 소스 파일 152개의 작업 트리 일치 검증 통과.
- API 35 ARM64 redroid에서 두 저장 형식의 파일·FD·신호·스레드·fork/exec·메모리 경계·dpkg 회귀 검증을 수행했습니다. statx/dirfd/storage 등 일부 검사는 마지막 prctl 정수 폭 보완 전 후보에서 수행했습니다.
- 최종 작은 파일 5,000개 읽기 중앙값 216.919ms는 이전 하드링크 기준 168.296ms보다 28.89% 길었습니다. 전체 동등 성능 목표는 미달성입니다.
- 삼성 SM-S947N Android 17 및 enforcing SELinux 실기기 검사는 미실행입니다. 기존 에뮬레이션 backing 파일이 디렉터리 재포장에 노출되는 제한은 남습니다.
- 시험 전용 주입 코드와 미채택 최적화는 생산 DSO에 포함하지 않았습니다.

첨부된 재현 소스의 BUILD.md에 빌드 방법을 기록했습니다. 성능 보고서 ROOTLESS_EMULATED_ACCELERATION.md에는 각 후보와 최종 빌드의 측정 범위를 구분했습니다. 런타임 아카이브와 소스 아카이브는 prepare-release-runtime.py의 고정 SHA-256으로 검증합니다.
