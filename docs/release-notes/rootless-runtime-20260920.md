# Rootless runtime build input — rootless-runtime-20260920

- 릴리즈 ID: `rootless-runtime-20260920`
- 출시일: 2026-09-20
- 대상: TERM의 Android ARM64 APK 빌드용 런타임 입력. 설치용 APK가 아닙니다.

최종 rename 경로 복사 최적화가 포함된 PRoot, 대응 로더, faststat/stdio 결합 glibc 라이브러리와 재현 소스를 제공합니다. TERM의 개발 APK가 이 파일을 포함하도록 연결하기 위한 고정 입력입니다. CI는 압축 파일과 개별 ELF의 SHA-256을 모두 확인합니다. 설치된 앱은 이 릴리즈에서 실행 파일을 다운로드하지 않습니다.

- PRoot: `603ac7d6c40af0b76b9691641738e3955040920c75ea13a812bd1144427b61a2`
- 로더: `2567ab8ad3b418a8804696342056052eef37835e50d200e9f4ff48aae564c237`
- 결합 DSO: `7641d9415322beb07154443c1b90e801cdbb2b845691c20655a08c17dba75674`

Android 엔진은 Bionic, guest DSO는 ARM64 glibc용입니다. 임의 엔진·DSO 조합은 지원하지 않습니다. Rootless의 커널·권한 제한은 유지됩니다. 일반 파일 stdio 버퍼링은 암묵적 flush 시점을 바꿀 수 있으며, 명시적 flush/fsync를 대신하지 않습니다.

가속+stdio+rename APK 빌드 및 앱/runtime/image 단위 검사 통과. Android 최종 조합에서 Native/Rootless USB 모의 통신, 실제 가속 활성화, 이전 전·후 동작, 마이그레이션 중단 복구를 `OK (2 tests)`로 확인했습니다. 물리 휴대폰·실제 USB 전송·전체 I/O 2배 목표 달성을 주장하지 않습니다. 다운로드 자료에는 재현 코드와 관련 라이선스가 포함됩니다.
