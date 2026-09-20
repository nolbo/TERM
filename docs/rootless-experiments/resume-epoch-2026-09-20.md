# FD 상태 조회의 실행 재개 세대 실험

2026-09-20, 기준 커밋 `021bce5`. **실험 전용이며 앱에 포함하지 않았다.**

기존 에뮬레이션 fstat는 FD 경로를 조회 전후 두 번 읽는다. 후보는 단독 FD 테이블
조건(`close_safe`)에서 raw fstat 뒤 한 번만 읽되, tracer가 실행을 재개할 때마다
올리는 세대가 같아야 결과를 사용한다. 신호나 추적 syscall이 끼어들거나 FD 공유가
발생하면 기존 경로로 돌아간다. 기존 identity sequence를 실험용 세대로 사용했으며,
이 동작을 제공하지 않는 엔진과 혼용되지 않도록 후보의 프로토콜 버전만 7로 올렸다.
현재 생산 프로토콜은 6을 유지한다.

API 35 ARM64 redroid의 같은 앱 UID·Ubuntu·warm cache 조건에서 비교했고,
양쪽 모두 openat2를 차단했다. 작은 파일 5,000개 읽기 3회 교대 측정 중앙값은
223.510→217.133ms로 2.85% 짧았다. 세 쌍 모두 후보가 짧았지만 표본이 작다.
이 결과는 전체 목표 달성이나 삼성 Android 17 성능을 입증하지 않는다.
[전체 수치](resume-epoch-2026-09-20.json).

후보의 ARM64 엔진·DSO 빌드와 기존 emulated-links/fd-test 검사가 통과했다.
추가 신호 주입 시험은 raw fstat 직후 처리기에서 dup3로 에뮬레이션 backing FD를
다른 일반 파일 FD로 교체한다. 재개 세대 검사 없는 대조판은 잘못된 inode/size/nlink로
실패했고 검사를 유지한 후보는 재조회해 올바른 결과를 반환했다. 주입 hook은
별도 시험 DSO에만 넣었으며 성능 측정 DSO에는 없다.

검증은 Android MCP로 수행했다. incremental 설치는 거부되어 streamed 설치로
진행했다. 신호 주입 APK 설치의 응답이 지연됐지만 동일 요청의 완료를 기다렸으며
재설치 요청을 중복 발행하지 않았다. 시험 뒤 `021bce5`의 엔진/DSO로 복원했다.
생산 소스 변경이 없어 canonical Gradle 빌드는 이번 실험에서 반복하지 않았다.

## 재현 소스와 바이너리 구분

- [후보 패치](resume-epoch-2026-09-20.patch)를 기준 소스의 별도 시험 복사본에 적용한다.
  기존 `PROOT_BUILD_FASTSTAT=1 PROOT_BUILD_RENAME_LOCAL=1` 엔진 빌드와
  `PROOT_BUILD_STDIO=1` DSO 빌드를 사용한다.
- 엔진 SHA-256: `7ef1af82bdb3b398cda49830fa2fab0800d9cf1e5eb44d28a7c177694eedcc7d`
- 측정 DSO SHA-256: `497384e45d1a7059459a85be69369ba17feb5c289a6508cc4ef6b75fea4dab22`
- 기준 엔진: `bfae28b9702ae825eb51a6c224ccbab98d46c18318512b27975fd8f7069de1e9`
- 기준 DSO: `ee80d5395091d22a07079e75c9f159f5378a7fb4ebf950804f424a18ea524034`

신호 시험에는 후보에 [시험 hook 패치](resume-epoch-signal-hook.patch)를 추가 적용하고
[시험 C 소스](resume-epoch-signal-test.c)를 컴파일한다. 실행 파일은
`-Wl,--export-dynamic-symbol=proot_epoch_test_hook`으로 hook을 공개해야 한다.
해당 DSO를 preload한 새 PRoot 프로세스에서 실행한다. 대조 DSO는
`resume_sequence != atomic_load(&identity->sequence)` 조건만 `0`으로 바꾼다.
대조판 종료 1, 후보 종료 0을 확인했다. 이 시험 hook이나 대조 DSO는 배포하면 안 된다.

## 적용 전 남은 조건

세대가 변하지 않았다는 사실만으로 모든 FD 변경 주체가 배제되는지는 아직 증명하지
못했다. 특히 비동기 FD 테이블 변경의 감지·비활성화 조건과 exec 이후의 초기 상태를
검토해야 한다. 32비트 세대 번호 순환도 처리해야 한다. 이를 해결한 뒤 신호·동시성
대조 시험을 보강하고 전체 회귀·성능 검증을 해야 한다. 현재 후보는 채택 보류이며
기존의 두 번 경로 확인을 그대로 유지한다.
