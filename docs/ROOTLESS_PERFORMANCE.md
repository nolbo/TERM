# Rootless PRoot 성능 개선

## 문제와 범위

Rootless Linux는 ARM64 프로그램을 Android 커널에서 PRoot로 실행한다.
현재 실행 경로에는 QEMU CPU 에뮬레이션이 없고, seccomp 가속을 기본적으로
시도한다. 이번 변경은 고정된 Termux PRoot 5.1.107.92의 내부 처리만 개선한다.
Native/AVF 자동 전환, 패키지 구성 변경, 세션 소유권 변경은 포함하지 않는다.

## 일반 파일 조회의 중복 처리 감소

PRoot의 `canonicalize()`는 경로의 구성 요소를 `lstat`로 확인한다.
이어 `link2symlink`가 변환된 경로의 하드링크 에뮬레이션 여부를 확인하면서,
일반 파일에도 실패하는 `readlink`를 반복한다.

`tools/rootless/proot-perf.patch`는 마지막 구성 요소를 확인한 정보를
같은 `translate_path()` 안에서만 전달한다. stat/lstat 계열 호출이며,
재귀 수준 0에서 성공적으로 확인한 비심볼릭 링크이고, 마지막 host 경로가
정확히 일치할 때만 중복 `readlink`를 생략한다.

- 정보는 다음 경로 변환 시작과 `TRANSLATED_PATH` 알림 종료에서 무효화한다.
- 커널 호출이나 서로 다른 시스템 호출 사이에 파일 상태를 캐시하지 않는다.
- 심볼릭 링크, 재귀 해석, 경로 불일치와 확인 실패는 기존 처리를 사용한다.
- open의 FD 추적, fstat, AT_EMPTY_PATH, 하드링크 수 보정과 반환 단계의
  새로운 `lstat`는 유지한다. fake root와 seccomp 반환 추적도 유지한다.
- 추적 대상당 경로 버퍼 약 4 KiB가 추가된다.

첫 시제품의 반환 단계 경로 문자열 캐시는 메모리 읽기 횟수를 줄였지만
실행 시간 개선이 확인되지 않아 포함하지 않았다. 장기간 경로/메타데이터
캐시는 다른 세션, Native 작업, 호스트 파일 변경을 정확히 무효화하기 어려워
이번 범위에서 제외했다. 추적 이벤트 루프 병렬화도 포함하지 않았다.

## 개발용 계측

PRoot를 시작하는 **호스트 환경**에서 다음을 설정한다.
일반 앱 실행은 이 변수를 전달하지 않으므로 계측 출력이 없다.
게스트 셸에서 나중에 설정해도 이미 시작된 PRoot에는 적용되지 않는다.

| 변수 | 동작 |
| --- | --- |
| `PROOT_PERF=1` | 정상 종료 시 stderr에 집계 한 줄 출력 |
| `PROOT_NO_PATH_FASTPATH=1` | 새 최적화를 비활성화하여 같은 바이너리로 비교 |

계측은 진입/반환 처리 횟수, 경로 변환, 경로 정규화의 lstat,
link2symlink 반환 처리의 lstat와 readlink, 문자열 읽기,
process_vm 읽기/쓰기 시도와 ptrace fallback, 최적화 적용/미적용 횟수를 센다.
시간, 파일 경로, 파일 내용과 토큰은 출력하지 않는다.
카운터는 단일 tracer 이벤트 루프가 소유하고 fork된 launcher에서는
집계가 중복 출력되지 않는다. 강제 종료에서는 집계를 보장하지 않는다.

## 재현과 회귀 검증

기존 `tools/rootless/build-proot-arm64.sh OUTPUT_DIRECTORY`는 플랫폼 패치와
성능 패치를 모두 적용하고 출력 SHA-256 및 16 KiB LOAD 정렬을 검증한다.
`PROOT_DOWNLOAD_CACHE`로 이미 확보한 입력을 사용해도 각 입력의 고정 digest를
먼저 검증한다. 소스/NDK/라이브러리 버전은 변경하지 않았다.

`tools/rootless/build-perf-test-apk.sh PAYLOAD_DIRECTORY OUTPUT_APK [BASELINE_PROOT]`
는 별도 `dev.terminal.prootperftest` APK에 테스트 바이너리를 패키징한다.
기존 앱의 업데이트·종료·데이터 초기화 없이 APK의 추출된 nativeLibraryDir
실행 파일을 테스트한다. SDK는 저장소의 `.android-sdk`를 기본 사용하며
`PROOT_TEST_SDK_ROOT`로 지정할 수 있다. 서명에는 기존 Android debug keystore를
사용하며 `PROOT_TEST_KEYSTORE`로 경로를 지정할 수 있다.
테스트 APK나 키는 커밋하지 않는다.

Android MCP로 테스트 APK를 설치하고, `pm path`로 현재 라이브러리 경로를
확인한다. 디버그 앱 UID로 실행할 때는 `run-as`를 사용한다.
PRoot/loader는 APK의 라이브러리 경로, rootfs와 임시 디렉터리는 기존
Rootless 설치를 사용한다. 테스트 스크립트만 게스트 `/perf-guest.py`에 bind한다.
생성한 테스트 APK와 기기에 복사한 스크립트는 검증 후 제거한다.

게스트에서 다음을 실행한다.

```sh
python3 -B /perf-guest.py
python3 -B /perf-guest.py --bench --iterations 3000 --label fast-1
```

첫 명령은 임시 디렉터리에서 하드링크의 inode/link count, lstat와
심볼릭 링크, dirfd 상대 경로, fstat/dup, AT_EMPTY_PATH/EBADF,
rename/unlink, 끊어진 링크, 긴 경로, 삭제된 열린 파일, fork, 시그널,
다른 스레드의 atomic 파일 교체를 확인하고 파일을 정리한다.
`--bench`는 stat/lstat/open과 `/bin/true` 100회 실행을 JSON으로 출력한다.
기준 바이너리, 새 바이너리, 최적화를 비활성화한 새 바이너리를 번갈아
실행하고, 속도 측정에서는 `PROOT_PERF`를 끈다.
Python이 없는 배포판에는 `perf-guest.sh`를 bind하여 `/bin/sh`로 실행한다.
이는 hardlink/symlink와 rename/unlink 검증의 부분 집합이며 Python 검증을
모두 수행한 것으로 보고하지 않는다.

## 2026-09-14 측정

Android 15/API 35 ARM64 redroid, 기존 Debian rootfs에서 각 조건을 7회
측정했다. 번갈아 실행하고 짝수 회차에는 순서를 뒤집었다.
stat/lstat/open은 같은 파일을 100회 warm-up 후 3,000회 처리했다.
다음은 중앙값이며 실제 휴대폰 또는 Native 대비 결과는 아니다.

| 작업 | 기존 PRoot | 변경 PRoot | 변경 PRoot 최적화 끔 |
| --- | ---: | ---: | ---: |
| stat 3,000회 | 159.736 ms | 154.626 ms | 158.608 ms |
| lstat 3,000회 | 159.142 ms | 154.991 ms | 157.716 ms |
| open/close 3,000회 | 368.834 ms | 368.885 ms | 370.821 ms |
| /bin/true 100회 | 142.307 ms | 142.984 ms | 139.529 ms |

기준 대비 stat 약 3.2%, lstat 약 2.6% 감소를 관찰했다.
open과 exec의 차이는 작고 개선을 주장하지 않는다.
작은 반복 측정이므로 실제 작업에 같은 비율을 적용하거나 성능 보장으로
해석하지 않는다. 초기 계측 비교에서 최적화 적용 429회만큼 l2s readlink가
682회에서 253회로 줄었고, 반환 단계 lstat는 두 조건 모두 667회였다.
계측은 메커니즘 확인용이며 동시 작업의 정확한 횟수는 실행마다 달라질 수 있다.

최종 재현 빌드의 입력/출력 SHA-256과 16 KiB LOAD 정렬 검증은 통과했다.
Android MCP에서 최종 바이너리의 최적화 사용/비활성화 두 조건으로
Debian 및 Ubuntu의 Python 회귀 검증을 통과했다. Arch 기본 환경에는
Python이 없어 셸 링크 검증 부분 집합을 두 조건으로 통과했다.
호스트의 Python 회귀 검증과 스크립트 문법 검사도 통과했다.
Android의 incremental APK 설치는 지원되지 않아 도구의 streamed 설치
fallback으로 설치를 완료했다.

JDK 17에서 다음 저장소 표준 검증은 통과했다. 생성된 debug APK의
`lib/arm64-v8a/libproot.so` digest가 검증된 입력 payload와 일치한다.
runtime 테스트 결과는 129개 통과, 1개 건너뜀이며 image는 4개 통과다.
건너뛴 archive extractor 테스트는 선택적 입력 `ROOTLESS_TEST_ARCHIVE`가
제공되지 않았기 때문이며, APK/PRoot 실행 검증을 대신하는 테스트가 아니다.

```sh
./gradlew :android-app:assembleDebug :android-runtime:testDebugUnitTest :image:test --console=plain
```

최초 샌드박스 실행은 기존 Gradle 캐시의 잠금 파일 쓰기가 제한되어
실패했으며, 캐시 쓰기가 허용된 실행으로 같은 명령을 완료했다.
테스트 APK와 기기에 복사한 두 테스트 스크립트는 검증 후 제거했다.

실제 휴대폰의 발열·배터리·장시간 실행, git/Python import/패키지 설치/빌드
전체 성능은 아직 검증하지 않았다. 다음 단계는 이 대표 작업에서 경로
정규화와 확장 처리의 비중을 측정하여 후속 최적화 대상을 정하는 것이다.
