# rootless-runtime-20260920-emulated.3

- 준비일: 2026-09-20. 공개일: 미정(로컬 준비, 미게시).
- 대상: ARM64 Rootless PRoot, APK 고정 빌드 입력과 재현 소스.
- 직전 개발 입력: rootless-runtime-20260920-emulated.2(미게시).
- 직전 공개 입력: rootless-runtime-20260920-emulated.1.

dpkg가 압축 해제·배치 중 반복하는 임시 경로 정리를 최적화했습니다.
`unlinkat(AT_REMOVEDIR)`를 경로 번역 후 한 번 실행하고 종료 정지를 생략합니다.
같은 호출 안에서 번역한 경로를 재사용해 게스트 메모리 왕복도 줄입니다.
실제 커널 반환값, 패키지 파일, 링크 처리와 데이터 동기화를 유지합니다.

앞선 `.2`의 정적 프로그램 raw fstat 개선도 포함합니다. 로더·combined DSO·ABI 8과
저장 형식은 그대로입니다. 새 APK 업데이트 후 새 세션에서 적용되며 앱 삭제,
배포판 재설치나 패키지 이전은 필요 없습니다. 실행 중인 세션을 강제 종료하지 않습니다.

API 35 ARM64 redroid·Ubuntu·앱 UID에서 직전 `.2` 엔진과 세 쌍 교차 비교한
1,000개 파일 시험 패키지의 dpkg unpack 중앙값은 1056.239→1008.919ms로
4.48% 짧았습니다. 한 쌍은 느려져 실제 기기의 개선율을 보장하지 않습니다.
전체 시험 syscall 종료 정지는 약 7,300→4,275회, 메모리 쓰기는 10,007→6,982회로
감소했습니다. 순수 압축 알고리즘 처리량이나 apt 전체 개선율은 아닙니다.

최종 ARM64 엔진 빌드와 두 저장 형식의 unlink 오류·메모리 경계·디렉터리·심볼릭
링크·seccomp 및 별도 dpkg 설치·업그레이드·삭제 검사가 통과했습니다.
새 로컬 입력을 지정한 canonical 앱 빌드/runtime/image 검증은 1분 19초에
성공했습니다. 변경 없는 runtime/image 단위 검사는 UP-TO-DATE로 재사용했습니다.
업데이트/런타임 입력 Python 검사 19개, 최종 APK payload 해시와 재현 소스
159개 파일 검증도 통과했습니다.
기존 에뮬레이션 raw stat의 링크 소유자 표시 문제는 남아 있습니다.
삼성 Android 17/enforcing SELinux, 실제 사용자 패키지 성능은 미검증입니다.

보고서: `docs/ROOTLESS_EMULATED_ACCELERATION.md`.
원본: `tools/rootless/faststat/results/unpack-unlink-2026-09-20.json`.
아카이브 미게시 상태에서는 `prepare-release-runtime.py --assets-directory`로
로컬 입력을 제공해야 하며 원격 CI/배포는 `.3` 아카이브 게시 후 진행해야 합니다.
