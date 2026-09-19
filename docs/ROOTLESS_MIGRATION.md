# 기존 Rootless 설치의 호환 마이그레이션

## 문제와 결정

구버전의 유효한 설치에는 `.terminal-storage-format` 표시가 없다. 가속 APK는
기존에 이 경우를 거부하여 패키지 유지보수와 새 PTY 실행이 실패했다.
업데이트 후 기존 설치를 다시 사용할 수 있도록 공통 실행 명세 생성 단계에서
저장 형식을 한 번 판별하고, 구버전에는 원래의 링크 에뮬레이션을 적용한다.

| 설치 상태 | 실행 방식 |
| --- | --- |
| 표시 없음 | 구버전 호환 모드, `--link2symlink`, 네이티브 링크 DSO/가속 플래그 없음 |
| `emulated-hardlinks-1` | 검증된 에뮬레이션 모드 유지 |
| `native-hardlinks-1` | 네이티브 링크 유지, 가속 APK에서만 가속 적용 |
| 알 수 없는 값·빈 값·초과 데이터·일반 파일 아닌 표시 | 변경 없이 오류, 구버전으로 간주하지 않음 |

표시를 읽을 때 심볼릭 링크를 따라가지 않고 길이를 제한한다. 표시가 없다는
판정은 접근 실패와 구분한다. 설치 자체의 유효성 확인은 기존 실행/준비 진입점에서
계속 수행한다. 설치 도중 실패한 불완전 이미지의 복구는 기존 설치 관리 정책을 따른다.

## 데이터와 세션 보존

마이그레이션은 **실행 정책 선택**이다. 기존 rootfs를 순회·복사하거나 표시 파일을
쓰지 않으며, 패키지 DB·심볼릭 링크·홈·워크스페이스도 변경하지 않는다. 원래의 링크
확장 기능이 기존 에뮬레이션 구조를 계속 해석한다. 반복 실행과 앱 프로세스 중단으로
부분 변환 상태가 생기지 않는다. 추가 이미지 다운로드나 변환용 여유 공간은 필요 없다.

현재 파일시스템이 하드링크를 지원한다는 이유만으로 기존 설치를 네이티브 형식으로
승격하지 않는다. 과거에 생성된 에뮬레이션 링크가 남아 있을 수 있기 때문이다.
네이티브 가속 성능은 이 호환 모드에 그대로 적용되지 않는다. 데이터 변환을 통한
가속 형식 승격은 이번 기능의 범위가 아니다.

마이그레이션은 실행 중인 세션을 종료하지 않는다. Android의 APK 업데이트 자체가
프로세스를 종료할 수 있으며 그 프로세스 상태를 복원하지는 않는다. 앱의 기존
환경 보완·패키지 설치 정책도 이 기능과 별개다. 그 작업까지 무변경이라고 보장하지 않는다.

## 검증 방법

- `RootlessStorageFormatTest`: 모든 배포판 레이아웃에서 두 번 실행해 링크 대상,
  파일 내용, 홈/워크스페이스, root 디렉터리 수정 시각, 표시 없음이 유지되는지 확인.
  손상·미래 형식·과도한 길이·심볼릭 링크 표시가 덮어써지지 않는지도 확인.
- `RootlessLaunchSpecTest`: 가속 라이브러리가 있는 APK도 구버전에는 링크 확장을
  사용하고 DSO를 주입하지 않는지, 네이티브·에뮬레이션 설치가 기존 모드를 유지하는지 확인.
- `RootlessStorageMigrationTest`: Android NIO에서 표시 없음·정상 형식·잘못된 형식과
  링크 표시를 직접 검사. 사용자 앱이 아닌 독립 시험 APK에서 실행.
- `RootlessFreshInstallTest`의 `rootlessLegacyMigration=true`: 격리된 배포판을 설치한
  다음 형식 표시를 제거하고 실제 PRoot 링크 에뮬레이션으로 구버전 파일을 생성.
  이후 패키지 구성·검증, PTY, 유지보수, 홈/작업 파일, 링크를 통한 쓰기와 반복 실행을 확인.
  사용자 설치를 변경하지 않는 재현 시험이며, 구버전 제품 APK를 실제 업그레이드한 시험은 아니다.

재현 시 별도 시험 APK의 instrumentation에 다음 인자를 지정한다:

```text
-e class dev.terminal.runtime.rootless.RootlessStorageMigrationTest,dev.terminal.runtime.rootless.RootlessFreshInstallTest
-e rootlessFreshInstall true
-e rootlessLegacyMigration true
-e rootlessDistribution DEBIAN
```

Ubuntu는 마지막 값을 `UBUNTU`로 바꾼다. 가속+stdio 시험 APK에서도 구버전에는
두 기능이 주입되지 않는지 검사한다. 실제 휴대폰 전체와 Arch의 기기 실행은 별도 검증이다.

## 2026-09-20 검증 기록

다음 빌드·단위 테스트가 통과했다. runtime 149개 중 148개 통과, 기존 선택적
아카이브 시험 1개 생략, image 4개 통과. 일반 프로필의 변경 부위 집중 시험도 통과했다.

```sh
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64 ./gradlew \
  :android-app:assembleDebug :android-runtime:testDebugUnitTest :image:test \
  :android-runtime:assembleDebugAndroidTest \
  -ProotlessAcceleratedPayload=/tmp/terminal-stdio-app-payload \
  -ProotlessStdioBuffer=true --console=plain
```

Android API35 ARM64 시험 환경에서 Android MCP로 별도 시험 APK를 설치했다.
Debian 구버전 재현과 저장 형식 판별은 `OK (2 tests)`, 93.762초로 통과했다.
Ubuntu 구버전 재현도 `OK (1 test)`, 291.598초로 통과했다. 두 배포판 모두
`LEGACY_MIGRATION_VERIFIED`가 기록됐고 임시 설치는 시험 종료 시 정리됐다.
이는 성능 측정이 아니라 통합 시험의 총 실행 시간이다.

첫 Debian 실행은 PTY 시험이 비활성화된 네이티브 가속 심볼을 요구하여 시간
초과로 실패했다. 호환 모드에서는 DSO 부재를 검사하도록 수정했으며, 앞으로
PTY가 실패 종료하면 출력과 함께 즉시 실패하도록 시험도 보완했다. fchmodat2,
권한·tar·PTY 검사는 제거하지 않고 재실행에서 통과했다.

증분 APK 설치 미지원으로 도구가 스트리밍 설치에 성공했다. 사용자 앱을 교체하거나
기존 사용자 배포판을 변경하지 않았다. UI 변경·새 외부 API·외부 서비스 작업이 없어
replicant/context7/codex_apps는 사용하지 않았다. 실제 제품 APK 업데이트 및
Arch/물리 휴대폰 검증은 수행하지 않았다.
