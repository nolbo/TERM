# 별도 설치용 디버그 APK

`./gradlew :android-app:assembleDebug --console=plain`으로 빌드한다.
산출물은 `android-app/build/outputs/apk/debug/android-app-debug.apk`다.

디버그와 배포용 APK는 패키징 단계만 매번 새로 수행한다. 증분 패키징에서
교체·삭제한 ZIP 항목의 자리가 빈 영역으로 남아 APK 용량이 누적되는 것을
방지한다. 컴파일 캐시는 재사용하므로 전체 `clean` 빌드는 필요하지 않다.
서명과 ZIP 정렬에 필요한 공간은 Android 빌드 도구가 정상적으로 생성한다.

| 항목 | 디버그 | 정식·프리릴리즈 (`release` 빌드) |
| --- | --- | --- |
| 앱 ID | `dev.terminal.app.debug` | `dev.terminal.app` |
| 앱 이름 | TERM Debug | TERM |
| 버전 이름 | 기존 버전 이름에 `-debug` 추가 | 기존 채널별 버전 이름 |
| 서명 | 로컬 Android 디버그 키 | 배포용 서명 키 |

두 앱은 동시에 설치할 수 있다. 설정, 프로필, Native 홈, Rootless 환경과
런타임 세션은 앱별로 독립적이며 기존 앱 데이터를 자동 복사하지 않는다.
공유 저장소 접근 권한은 각 앱에 별도로 부여하며, 사용자가 양쪽에 공유한
외부 파일은 같은 파일이다. AVF 특권 설정도 디버그 앱에 자동 승계되지 않는다.

TERM Debug의 앱 업데이트 화면에서는 정식·개발 채널 업데이트를 실행하지 않는다.
디버그 앱 갱신은 같은 디버그 키로 서명한 APK를 다시 설치한다.
기존에 `dev.terminal.app`으로 설치했던 디버그 APK도 이 새 앱으로 자동 이전되지 않는다.

이 변경은 로컬 디버그 빌드의 설치 구분을 위한 것이며 릴리스 게시가 아니다.
배포용 앱 ID·서명·채널 정책과 기본 버전 `0.2.0`은 유지한다.
