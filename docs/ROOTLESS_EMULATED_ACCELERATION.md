# 하드링크 제한을 받는 Android의 Rootless 가속

2026-09-20 개발 수정. 아직 공개 APK에 포함되지 않았다. 마지막 공개판
`dev-09afdf17440bed2e3925b52be694dbf07699abc9`은 오류 진단만 개선한 버전이다.

## 문제와 결정

기존 가속은 실제 하드링크로 변환한 설치에서만 켜졌다. 그러나 AOSP의
[앱 SELinux 정책](https://android.googlesource.com/platform/system/sepolicy/+/refs/heads/main/private/app_neverallows.te)은
`all_untrusted_apps`의 `file_type:file link`를 금지한다. 이 정책 아래에서는
`Files.createLink`를 다른 API로 바꿔도 해결되지 않는다. 보고된 Java 예외만으로
삼성 기기의 정확한 AVC 거부 원인을 확정할 수는 없지만, 이 제한을 가속의
필수 조건으로 둔 설계는 일반 Android에 적합하지 않다.

또한 기존 가속이 사용한 `openat2`는 Android Bionic의
[시스템 호출 목록](https://android.googlesource.com/platform/bionic/+/refs/heads/main/libc/SYSCALLS.TXT)과
[공통 seccomp 추가 허용 목록](https://android.googlesource.com/platform/bionic/+/refs/heads/main/libc/SECCOMP_ALLOWLIST_COMMON.TXT)에 없다.
기존 시험 대상은 SELinux 비활성 redroid여서 이 플랫폼 조건을 충분히 반영하지 못했다.

새 방식은 기존 하드링크 에뮬레이션을 유지하고 일반 파일 경로를 가속한다.
앱 업데이트 후 새 세션에 적용하며 패키지·홈·workspace를 다시 쓰거나
저장 형식 표시를 임의 변경하지 않는다. 새 설치도 하드링크 시험 실패 시
에뮬레이션 형식으로 설치하고 같은 가속을 적용한다. 이전 버튼은 중단된
변환 기록이 있을 때만 **중단된 이전 복구**로 표시하며 `recover()`만 수행한다.

## 구현과 호환성

- tracer/DSO 공유 프로토콜을 버전 5로 함께 변경했다. 일반 stat/open/create/close와
  ID 조회는 에뮬레이션에서도 가속한다. 과거 DSO와 혼용하지 않도록 빌드 해시를 고정했다.
- `openat2` 호출을 제거했다. 게시된 host 바인딩 루트를 매번 새 FD로 열고,
  guest 경로의 각 요소를 `openat(O_PATH|O_NOFOLLOW)`로 확인한다. 중간 요소는
  디렉터리여야 한다. 링크·복잡한 경로·오류는 PRoot의 기존 해석기로 돌린다.
  host 바인딩 루트의 조상은 host가 선택한 경로이며 guest suffix와 구별한다.
- 열린 FD의 하드링크 전환을 놓치지 않도록 fstat 앞뒤에 실제 FD 경로를 확인한다.
  숨겨진 하드링크 backing 파일은 PRoot가 가상 링크 수를 처리한다. 경로나 메타데이터를
  호출 간 캐시하지 않는다. 마지막 링크 삭제 뒤 열린 FD의 fstat 오류도 수정했다.
- 에뮬레이션의 unlink는 tracer에서 링크 수 갱신을 거친다. 일반 파일은 진입 시
  완료하여 불필요한 syscall 종료 정지를 줄인다. rename은 확장 콜백에 현재 호출의
  지역 경로를 제공해 게스트 메모리 복사를 줄이고, 같은 포인터/서로 다른 dirfd 등은
  안전한 기존 경로로 돌린다. 디렉터리·특수 파일·지원하지 않는 확장은 fallback한다.
- 커널 권한, SELinux, 상속된 seccomp를 해제하지 않는다. PRoot 자체의 Linux 호환성
  한계는 남으며 이 변경이 일반 커널/namespace 지원을 제공하지는 않는다.

## 성능

[원시 결과와 중앙값](../tools/rootless/faststat/results/emulated-links-2026-09-20.json).
API 35 ARM64 redroid의 앱 UID, Ubuntu 24.04 사용자 공간, 동일 저장소에서
3회 순서를 번갈아 측정했다. 매 작업은 새 프로세스와 임시 디렉터리를 사용했다.
파일 1,000개, 순차 I/O 64 MiB, warm cache, 진단과 NSS 캐시는 모두 껐다.
새 방식에는 tracer와 guest 모두 `openat2`를 SIGSYS로 차단하는 필터를 적용했다.

| 작업 | 가속 전(ms) | 이전 네이티브 가속(ms) | 새 에뮬레이션 가속(ms) | 가속 전 대비 |
| --- | ---: | ---: | ---: | ---: |
| 파일 정보 조회 | 61.33 | 13.36 | 20.72 | 2.96배 |
| 생성·쓰기·이름 변경·삭제 | 361.68 | 209.14 | 272.46 | 1.33배 |
| Python import | 505.79 | 442.11 | 448.13 | 1.13배 |
| tar 압축 해제 | 739.71 | 524.06 | 607.35 | 1.22배 |
| 작은 파일 읽기 | 183.17 | 34.55 | 52.05 | 3.52배 |
| 순차 읽기 | 13.15 | 13.46 | 13.50 | 0.97배 |
| 순차 쓰기 | 13.55 | 13.93 | 13.78 | 0.98배 |
| fsync 포함 쓰기 | 283.44 | 278.47 | 290.72 | 0.97배 |

정보 조회·작은 파일 읽기는 이전 방식이 절약했던 시간의 약 85~88%, import는
약 91%를 유지했다. 파일 변경 묶음은 약 59%, tar는 약 61%로 보존 폭이 작다.
이전 네이티브 방식보다 각각 약 30%, 16% 느리므로 모든 작업의 가속 효과가
그대로라고 주장하지 않는다. 실제 하드링크가 금지된 기기에서도 적용할 수 있다는
차이가 있다. 순차 I/O 향상은 없으며 공유 저장소의 변동이 포함된다. 이 수치를
SM-S947N Android 17의 체감 성능이나 전체 패키지 설치 배속으로 일반화하지 않는다.

## 검증 및 재현

- 엔진/DSO ARM64 빌드 및 16 KiB 정렬 검사 통과.
- Android 앱 빌드 통과. runtime 160개·app 74개·image 4개 테스트 통과.
  외부 압축 파일을 명시적으로 받는 runtime 테스트 1개는 입력 미지정으로 건너뛰었다.
  업데이트/입력 검증 Python 테스트 19개도 통과했다.
- `android-policy-test.c`로 tracer와 guest에 openat2 금지 필터를 상속한 상태에서
  stat/open/create/close, bind/unmount/chroot, cwd/dirfd, rename 경계 메모리·동일 포인터,
  unlink/열린 FD, 하드링크 별칭·링크 수, guest seccomp 회귀 시험 통과.
- `emulated-package-test.py`: 별도 dpkg DB에 실제 tar 하드링크 항목을 가진 패키지를
  설치·업그레이드·삭제하는 시험 통과. 사용자 시스템의 dpkg DB는 변경하지 않는다.
- Debian 새 설치에서 Java 하드링크 생성 거부를 주입하고 구버전 무표시 형식으로
  가속 실행, 파일/홈/workspace 보존, stdio·PTY·NSS 검증 통과.
- Ubuntu 새 설치: 하드링크 생성 거부 주입 상태에서 다운로드·압축 해제·공식 패키지
  설치/업그레이드·stdio·PTY 검증 통과. 처음에는 여유 공간 4GiB 기준에 막혔으며
  재다운로드 가능한 도구 설치 압축 파일만 정리한 뒤 재시도했다.
- 별도 UI 시험 패키지: 무표시 구버전 설치에 전환 버튼이 없음, STAGING 중단 기록을
  넣으면 해당 Ubuntu에만 복구 버튼 표시, 클릭 후 기록 제거·저장 형식 미변경 확인.
- Android MCP로 설치/실행/로그를 검증했고 Replicant로 설정 UI를 조작했다.
  Replicant의 별도 패키지 실행이 PACKAGE_NOT_FOUND를 반환해 Android MCP에서
  실제 Activity를 조회해 실행했다. 중복 텍스트 선택은 확인된 좌표로 보완했다.
  context7/codex_apps는 이 로컬 런타임·기기 검증에 추가 근거가 없어 사용하지 않았다.

제한 주입 시험은 삼성의 enforcing SELinux 실기기 검증을 대체하지 않는다.
그 기기의 전체 seccomp/SELinux 정책이나 Android 17 제조사 변경은 아직 미검증이다.
기존 `native-links-package-test.sh`의 하드링크 디렉터리 전체 재포장 시험은
에뮬레이션 backing 이름을 tar가 순회해 실패하며 가속 전 엔진에서도 재현된다.
이번 dpkg 시험은 실제 패키지처럼 명시적인 tar hardlink 항목으로 검증하며,
기존 PRoot의 backing 파일 노출 문제 전체를 해결했다는 주장은 하지 않는다.

재현 빌드:

```sh
PROOT_BUILD_FASTSTAT=1 PROOT_BUILD_RENAME_LOCAL=1 \
  bash tools/rootless/build-proot-arm64.sh /tmp/emulated-runtime
PROOT_BUILD_STDIO=1 bash tools/rootless/faststat/build.sh /tmp/emulated-runtime
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64 ./gradlew \
  :android-app:assembleDebug :android-runtime:testDebugUnitTest :image:test \
  :android-app:testDebugUnitTest :android-runtime:assembleDebugAndroidTest \
  -ProotlessAcceleratedPayload=/tmp/emulated-runtime -ProotlessStdioBuffer=true --console=plain
```

`emulated-run.sh`는 시험 APK nativeLibraryDir, 읽기 전용 배포판 rootfs, 쓰기 가능한
시험 cache를 인수로 받는다. `restricted` 모드는 APK 안의
`libandroid-policy-test.so` 실행 파일을 필요로 한다. 회귀 Python 파일을 표준 입력으로
전달한다. `workloads.py`도 같은 방법으로 전달하며 `--count 1000 --io-mib 64 --case ...`를
뒤에 붙인다. 정책 helper는 Android NDK ARM64 PIE 실행 파일로 빌드한다.

배포 입력은 `rootless-runtime-20260920-emulated.1`로 준비한다. 소스/바이너리
압축 파일의 고정 해시 검증과 APK 내 payload 검증을 수행한다. 공개 입력 저장소에
새 파일을 게시하기 전에는 해당 입력을 다운로드하는 채널 CI를 실행할 수 없다.
로컬 빌드는 위 경로 또는 `prepare-release-runtime.py --assets-directory`로 재현한다.
