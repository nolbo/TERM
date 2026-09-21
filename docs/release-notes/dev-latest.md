# TERM 개발 채널 — 세션 프로필 및 작업 알림

- 릴리즈 ID: `dev-latest` → `dev-e21444620470c1dc15c22aade559fdc97b91a74c`
- 앱 버전: `0.1.0+dev.1000030.e21444620470` (versionCode 1000030)
- 출시일: 2026-09-21
- 대상: TERM Android ARM64 APK, Native/Rootless Debian/Ubuntu/Arch/AVF 세션

## 변경 사항

- built-in 및 사용자 SessionProfile을 지원하고, 프로필을 resolve한 불변 SessionLaunchSpec으로 세션을 생성합니다. 실행 중인 세션은 생성 당시 설정을 보존합니다.
- 새 세션 메뉴와 Settings의 Profiles에서 프로필을 추가·편집·복제·삭제할 수 있습니다.
- 오래 걸린 작업 완료와 추가 입력 필요 상태를 선택적으로 Android 알림으로 표시합니다.
- 기존 세션 복원, 탭 전환, 명시적 종료 lifecycle을 유지합니다.

## 업데이트 및 제한

기존 세션과 사용자 프로필은 업데이트 후 유지됩니다. built-in 프로필은 직접 수정·삭제할 수 없고 복제할 수 있습니다. AVF는 지원 기기에서만 실행됩니다.

## 검증

- 앱·runtime·image 빌드 및 단위 검사 성공: app 76개, runtime 176개(선택 검사 1개 생략), image 4개.
- Android instrumentation 11개 통과.
- 게시 CI에서 서명 release APK와 Rootless 런타임 검증을 수행합니다.
