# 독립 Rootless 새 설치 시험

사용자의 `dev.terminal.app`을 교체하지 않는 library instrumentation 시험이다.
테스트 APK와 target package는 모두 `dev.terminal.runtime.test`이며, 실제
RootlessBootstrapper와 RootlessEnvironmentSetup을 테스트 전용 임시 저장소에서
실행한다. 기본 실행은 skip이며 명시적 인자가 있어야 네트워크 설치를 수행한다.

## 실행

```sh
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64 ./gradlew \
  :android-runtime:assembleDebugAndroidTest --console=plain
adb install -r -t android-runtime/build/outputs/apk/androidTest/debug/android-runtime-debug-androidTest.apk
adb shell am instrument -w \
  -e class dev.terminal.runtime.rootless.RootlessFreshInstallTest \
  -e rootlessFreshInstall true -e rootlessDistribution DEBIAN \
  dev.terminal.runtime.test/androidx.test.runner.AndroidJUnitRunner
```

Codex는 설치·실행·로그 관찰에 Android MCP를 사용한다. 위 명령은 수동
재현용이다. 배포판 인자는 `DEBIAN`, `UBUNTU`, `ARCH`이며 시험하지 않은
배포판의 성공을 다른 배포판 결과에서 추정하지 않는다.

시험은 고정 이미지 다운로드·검증·추출, 새 native-link 저장 형식 선택,
공식 기본 패키지 구성, 환경 검증과 실제 하드링크의 unlink 후 내용 보존을
확인한다. finally에서 소유한 임시 트리만 정리하며 사용자 앱의 세션이나
패키지 데이터베이스를 사용하지 않는다. APK의 PRoot는 기본 payload다.
기본 실행과 실험 payload 실행의 결과를 아래에서 구분한다.

## 2026-09-15 결과

- APK 빌드 및 표준 Gradle 검증 통과: 170 tasks, 7 executed.
- 패키징된 manifest의 test/target package와 native library 추출 설정 확인.
- 초기 Kotlin 접근 범위 및 native library 패키징 오류는 수정했다.
- 첫 APK 설치는 공간 부족으로 실패했다. 사용자 승인 후 재생성 가능한
  `/tmp/sidestore-worker-tests/target` Cargo 캐시와 현재 settings에서 제외된
  `app/build` 산출물을 정리해 여유 공간을 약 464MiB에서 4.5GiB로 확보했다.
  소스, SDK, 사용자 앱 데이터와 기존 배포판은 보존했다.
- 첫 instrumentation 실행은 JUnit이 void 반환을 요구하여 초기화에 실패했다.
  `runBlocking<Unit>`으로 수정하고 시험 APK 재빌드에 성공했다
  (`:android-runtime:assembleDebugAndroidTest`, 105 tasks, 5 executed).
- Android MCP로 재설치·실행한 Debian 새 설치 시험 **통과**:
  `OK (1 test)`, 89.251초. 이미지 검증·추출, native-link 저장 형식,
  공식 기본 패키지 설치, 환경 검증, 하드링크 unlink 후 내용 보존을 확인했다.
- 시험 임시 rootfs가 finally에서 정리되고 여유 공간이 약 4.5GiB로 복구되었다.
  종료 후 dpkg 로그 조회는 임시 트리가 이미 삭제되어 실패했으며,
  별도 instrumentation 결과 파일에서 성공을 확인했다.

이 결과는 ARM64/API 35 redroid의 앱 UID에서 얻었다. stock Android 정책,
Ubuntu/Arch 새 설치는 미검증이다.
89.251초는 이번 시험 전체 시간이며 기존 대비 성능 비교 수치가 아니다.

## 실험 kernel-stat payload의 새 설치

같은 시험 APK에서 `lib/arm64-v8a/libproot.so`만 실험 빌드로 교체하고 debug
서명한 별도 APK를 사용했다. 사용자 앱이나 저장소의 기본 payload는 교체하지
않았다. 설치 시험 인자로 다음을 추가한다:

```sh
-e rootlessStatKernel true \
-e rootlessProotSha256 511529bff26b6c68a0d467af494594b897fc33bd274e25064f35ad1577f28832
```

시험은 APK에서 추출한 실제 PRoot의 digest가 일치해야 진행한다.
RootlessEnvironmentSetup 및 마지막 하드링크 검증 프로세스에만
`PROOT_STAT_ENTRY=1 PROOT_STAT_KERNEL=1`을 전달한다. 일반 실행의 기본값은
꺼짐이다. 집중 단위 시험은 옵션을 켠 프로세스에 두 값이 전달되고, 이어서
실행한 일반 프로세스에는 전달되지 않음을 확인했다.

2026-09-15 ARM64/API 35 redroid 결과:

- 실험 APK 재서명 검증과 Android MCP 설치 성공. incremental 설치가 거부되어
  도구가 streamed 설치로 전환했다.
- 새 Debian 이미지 설치, 공식 기본 패키지 설치·환경 구성, 하드링크 검증
  **통과**: `OK (1 test)`, 90.722초. 임시 rootfs는 자동 정리되었다.
- 표준 Gradle 검증과 시험 APK 빌드 통과(170 tasks, 15 executed).
- 추가 RootlessSetupCommandTest 집중 검증 통과(52 tasks, 3 executed).

기본 payload 시험 89.251초와 실험 시험 90.722초는 네트워크와 패키지 서버를
포함하는 각각 한 번의 실행이다. 성능 배속 계산에 사용하지 않는다. 이 결과는
가속 옵션을 켠 설치의 정상성 증거이며 전체 작업 2배 또는 stock 기기의
seccomp/SELinux 정책 지원을 증명하지 않는다.

## 일반 경로 가속을 추가한 설치

2026-09-15, PRoot
`a19c562a50f26102415347e63118d23d3bf252134b68fa4693c5165ba75b6326`을 같은 방식으로
시험 APK에 패키징했다. 앞의 실행 명령에서 digest를 이 값으로 바꾸고
`-e rootlessPathKernel true`를 추가한다. 이 옵션은 digest 검증이 있는
`rootlessStatKernel=true`와 함께만 사용할 수 있다.

- Android MCP의 새 Debian 설치·기본 패키지 구성·환경·하드링크 시험 통과:
  `OK (1 test)`, 87.349초. 임시 rootfs는 자동 정리되었다.
- 표준 Gradle 검증과 Android 시험 APK 빌드 통과(170 tasks, 15 executed).
  확장한 옵션 격리 단위 시험도 포함한다.
- 초기 PRoot 빌드의 계측 상수 이름 오류는 수정 후 빌드에 성공했다.
- incremental 설치 미지원은 도구의 streamed 설치로 해결했다. 한 번의 읽기
  전용 기기 조회가 자동 검토 서버의 용량 오류로 거부되었으나 재조회는 성공했다.

이번 설치 시간도 배속 비교에 사용하지 않는다. 변경한 기본 앱 payload는 없으며,
stock Android와 F2FS 정책·특이 동작 검증은 남아 있다.
