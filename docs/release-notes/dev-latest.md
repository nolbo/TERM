# TERM 개발 채널 — 세션 프로필 및 작업 알림

- 릴리즈 ID: `dev-latest` → `dev-2518563d77972d921e0f7fe61b00f716e700cb67`
- 앱 버전: `0.1.0+dev.1000030.2518563d7797` (versionCode 1000030)
- 출시일: 2026-09-21

## 변경 사항

- built-in 및 사용자 SessionProfile과 불변 SessionLaunchSpec 기반 세션 생성을 지원합니다.
- Profiles 화면에서 프로필을 추가·편집·복제·삭제할 수 있습니다.
- 작업 완료와 추가 입력 필요 상태를 선택적으로 Android 알림으로 표시합니다.
- 기존 세션 lifecycle을 유지합니다.

## 검증

- 앱·runtime·image 단위 검사와 Android instrumentation 통과.
- Debug APK SHA-256: `25447c7dcf342f7d46c40e328249c5b2cd31501f15ffa6240327aaea0d6d53b2`.
- 게시 workflow는 Rootless 런타임 자산 URL 404로 중단되어 debug APK를 직접 첨부했습니다.
