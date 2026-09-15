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
실험 kernel-stat payload로 전체 설치를 검증한 시험은 아직 아니다.

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
Ubuntu/Arch 새 설치, 실험 kernel-stat payload의 전체 설치는 미검증이다.
89.251초는 이번 시험 전체 시간이며 기존 대비 성능 비교 수치가 아니다.
