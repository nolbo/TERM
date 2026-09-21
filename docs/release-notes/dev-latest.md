# TERM 개발 채널 — 세션 프로필 및 작업 알림

- 릴리즈 ID: `dev-latest`
- 앱 버전: CI 실행 시 부여되는 개발 채널 버전
- 출시일: 2026-09-21

## 변경 사항

- built-in 및 사용자 SessionProfile과 불변 SessionLaunchSpec 기반 세션 생성을 지원합니다.
- Profiles 화면에서 프로필을 추가·편집·복제·삭제할 수 있습니다.
- 작업 완료와 추가 입력 필요 상태를 선택적으로 Android 알림으로 표시합니다.
- 기존 세션 lifecycle을 유지합니다.

## 검증

- 앱·runtime·image 단위 검사와 Android instrumentation 통과.
- 게시 CI가 Rootless 런타임 고정 입력을 검증한 뒤 서명된 release APK만 첨부합니다.
