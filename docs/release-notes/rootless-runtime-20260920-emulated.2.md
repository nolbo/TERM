# rootless-runtime-20260920-emulated.2

- 준비일: 2026-09-20. 공개일: 미정(로컬 준비, 미게시).
- 대상: ARM64 Rootless PRoot 및 TERM APK의 고정 빌드 입력·재현 소스.
- 비교 기준: rootless-runtime-20260920-emulated.1.

에뮬레이션 하드링크 환경에서 일반 파일의 raw fstat 처리를 단축합니다. 정적
바이너리와 직접 syscall에도 적용하며 FD를 고정한 뒤 현재 이름을 확인합니다.
backing 파일·특수 파일·확인 실패는 기존 처리로 돌립니다. 로더, combined DSO,
공유 ABI 8과 저장 형식은 유지합니다. fsync나 Android 보안 정책을 완화하지 않습니다.

새 엔진을 포함한 APK 업데이트 후 새 세션에서 적용됩니다. 기존 패키지·파일 이전,
앱 삭제, 배포판 재설치는 필요 없습니다. 실행 중인 세션을 강제 종료하지 않습니다.

API 35 ARM64 redroid 앱 UID에서 세 쌍 교차 측정한 중앙값은 raw fstat 10,000회
365.357→254.638ms(30.30% 감소), 로컬 시험 패키지 1,000개 파일 dpkg unpack
1106.904→1043.350ms(5.74% 감소)입니다. Codex나 apt update 전체 개선율은 아닙니다.

엔진 빌드와 정적 fstat 오류·메모리·동시 FD 교체·링크·seccomp 검사가 통과했습니다.
두 저장 형식의 shared stat, 스레드·fork/exec·FD 재사용, 별도 dpkg 설치·업그레이드·
삭제도 통과했습니다. 기존 stat-entry 검사는 네이티브에서 통과했으나 에뮬레이션에서
기존 공개 엔진과 후보 모두 108행 UID 검사가 실패했습니다. raw newfstatat의 기존
에뮬레이션 링크 소유자 표시 문제이며 이번에 해결하지 않았습니다.

새 로컬 입력을 지정한 canonical 앱 빌드/runtime/image 검증은 1분 31초에 성공했습니다.
변경 없는 runtime/image 단위 검사는 UP-TO-DATE 결과를 재사용했습니다. 업데이트/
런타임 입력 Python 검사 19개, 최종 APK payload 해시, 재현 소스 156개 파일 검증도
통과했습니다. 아카이브 미게시 상태에서는 로컬 `--assets-directory` 입력이 필요하며
원격 다운로드를 사용하는 CI는 아카이브 게시 후 실행해야 합니다.

삼성 Android 17, enforcing SELinux, 실제 Codex 및 apt update 성능은 미검증입니다.
이전 하드링크와 전체 동등 성능 목표는 미달성입니다. 자세한 조건과 원본은
`docs/ROOTLESS_EMULATED_ACCELERATION.md` 및
`tools/rootless/faststat/results/emulated-fstat-entry-2026-09-20.json`에 있습니다.
