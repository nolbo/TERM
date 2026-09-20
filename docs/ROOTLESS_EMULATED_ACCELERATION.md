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

- tracer/DSO 공유 프로토콜을 버전 6으로 함께 변경했다. 일반 stat/open/create/close와
  ID 조회는 에뮬레이션에서도 가속한다. 과거 DSO와 혼용하지 않도록 빌드 해시를 고정했다.
- `openat2` 호출을 제거했다. 게시된 host 바인딩 루트를 매번 새 FD로 열고,
  guest 경로의 각 요소를 `openat(O_PATH|O_NOFOLLOW)`로 확인하는 기본 경로를 유지한다.
  DSO는 아래의 실제 경로 대조가 성공하면 요소별 확인을 생략한다. 중간 요소는
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

## 최초 대응판(b64eeb6) 성능

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

## 추가 경로 최적화

후속 변경은 FD를 고정하는 함수의 중복 fstat를 제거하고, 실제 처리 단계의
stat 결과로 최종 파일 종류를 검사한다. 디렉터리만 허용하는 호출은 O_DIRECTORY를
유지한다. O_PATH는 최종 심볼릭 링크 자체를 열 수 있으므로 반환 FD를 바로 다시
열어서는 안 된다. 모든 호출자를 검사했으며 최종 링크·특수 파일은 계속 fallback한다.

게스트 경로는 우선 128바이트만 안전하게 복사하고 NUL이 없을 때 나머지를 읽는다.
현재 호출에서 이미 얻은 PID도 재사용한다. 페이지 경계나 읽을 수 없는 포인터를
직접 역참조하지 않으며 긴 경로는 PATH_MAX까지 처리한다.

[5회 교차 비교 원시 결과](../tools/rootless/faststat/results/pinned-path-2026-09-20.json).
직전 b64eeb6과 후속 후보를 같은 실행에서 번갈아 측정했으며, 양쪽 모두 하드링크
에뮬레이션과 openat2 금지를 적용했다. 환경·파일 수는 앞 절과 같다.

| 작업 | 직전(ms) | 후속(ms) | 시간 감소 |
| --- | ---: | ---: | ---: |
| 파일 정보 조회 | 20.335 | 19.507 | 4.07% |
| 작은 파일 읽기 | 52.214 | 49.363 | 5.46% |
| 파일 생성·변경·삭제 | 265.425 | 262.750 | 1.01% |
| tar 압축 해제 | 601.778 | 598.020 | 0.62% |
| Python import | 442.824 | 441.156 | 0.38% |

조회·작은 파일 읽기는 각 5쌍 모두 개선됐다. 다른 항목은 차이가 작고 변동이 있어
확실한 속도 향상으로 보지 않는다. 앞 절과 다른 실행의 절대 시간을 나눠 추가 배속을
계산하지 않는다. 순차 I/O는 이 변경이 read/write/fsync를 바꾸지 않아 다시 측정하지 않았다.
네이티브 전환 방식과의 성능 차이를 모두 회복한 것은 아니다.

후속 검증은 Android MCP를 통한 에뮬레이션/네이티브 양쪽의 stat/open/create/close,
경로 매핑·cwd·rename·unlink·dpkg 회귀 시험과 에뮬레이션 링크 수 시험이다.
127/128/129/200바이트 경로, 읽기 전용 페이지 끝의 NUL, NUL 없이 다음 페이지가
접근 불가능한 경로도 검사했다. 상속된 openat2 금지와 guest seccomp 검사는 유지했다.
APK 빌드·runtime/app/image 테스트와 업데이트 입력 검증도 다시 통과했다.
선택적 외부 압축 파일 테스트 1개는 앞서와 같이 입력 미지정으로 건너뛰었다.
UI 동작과 설치 절차는 변경하지 않아 앞 절의 UI/배포판 전체 설치 시험은 반복하지 않았다.
Android 17 삼성 실기기 미검증이라는 제한은 같다.

## statx를 사용하는 파일 목록 도구 가속

`statx`는 glibc/Linux의 확장 파일 정보 조회 API다. Ubuntu의 `ls`는 이 심볼을
사용하지만 기존 DSO는 이를 가속하지 않았다. 일반 파일/디렉터리 경로에서 공유
바인딩 검증과 no-follow FD 고정을 거쳐 같은 statx syscall을 호출하도록 추가했다.
하드링크 에뮬레이션·심볼릭 링크·특수 파일·지원하지 않는 인자는 기존 libc/PRoot
경로로 돌아간다. 닫기 가속의 파일 종류 검사는 파이프와 FD 추적을 보호하므로 유지했다.

[statx 계약](https://man7.org/linux/man-pages/man2/statx.2.html)에 따라 요청 mask와
동기화 flags를 전달하고 커널이 반환한 256바이트 구조체를 유지한다. 가상 UID/GID만
기존 fake-root 규칙으로 보정하며 생성 시각·mount ID·attributes를 stat에서 합성하지
않는다. NDK r28 헤더의 Android 도입 API는 30이며 앱의 최소 API는 31이다.
새 시스템 권한이나 openat2 허용은 요구하지 않는다.

[5회 교차 비교 원시 결과](../tools/rootless/faststat/results/statx-2026-09-20.json).
직전 `d0828fc`와 비교했고 양쪽 모두 동일 Ubuntu, 하드링크 에뮬레이션, openat2
금지 필터를 사용했다. 매 실행마다 일반 파일 1,000개를 준비했다. 명령 시작과 출력
수집은 시간에 포함하고 파일 준비·삭제는 제외했다. 최종 측정 중 다른 빌드/시험을
진행하지 않았다.

| 명령 | 직전(ms) | statx 가속(ms) | 시간 감소 |
| --- | ---: | ---: | ---: |
| ls -ln --color=never | 134.987 | 95.090 | 29.56% |
| du -s | 68.193 | 69.428 | -1.81% |
| find -ls | 157.437 | 157.421 | 0.01% |

ls는 5쌍 모두 개선됐다. du/find는 뚜렷한 변화가 없으며 전체 파일 작업의
30% 개선으로 해석하지 않는다. 앞 절의 조회/읽기와 다른 작업이므로 개선율을 합산하지 않는다.

`statx-test.py --expect-fast`는 실제 statx 조회 직후 가속 카운터 증가, 전체 결과의
기존 syscall과 바이트 단위 일치, 다양한 mask/동기화 flags, 실시간 크기 변경,
링크 수·링크 종류, dirfd/AT_EMPTY_PATH, EFAULT/읽기 전용 출력, seccomp 거부를 검사했다.
하드링크 별칭을 다른 경로에 bind한 경우도 검증했다. 초기 후보에서 발견한 링크 수
오류는 host 경로의 에뮬레이션 backing 파일을 판별해 기존 처리로 돌리도록 수정했다.
에뮬레이션과 네이티브 양쪽에서 통과했고, 기존 stat/open/create/close/경로/cwd/rename/
unlink/dpkg 시험도 양쪽에서 통과했다. PRoot 없는 호스트의 libc fallback도 확인했다.
잘못된 출력 포인터 시험에서 PRoot POKEDATA 경고가 출력되지만 EFAULT를 반환하고
시험 프로세스는 정상 완료한다. 직전 엔진에서도 같은 경고와 결과가 재현된다.
최종 가속 파일을 포함한 APK 빌드와 runtime/app/image 테스트도 통과했다.
선택적 외부 압축 파일 테스트 1개는 입력 미지정으로 건너뛰었다. 업데이트/입력 검증
Python 테스트 19개도 통과했다. 이 변경은 UI나 설치 절차를 바꾸지 않아 새 배포판
전체 설치/화면 시험은 반복하지 않았다. Android MCP로 실행을 검증했으며 UI 변경이
없어 이번 추가 작업에서 Replicant는 사용하지 않았다.

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

## 실제 경로 대조를 이용한 추가 개선

전체 host 경로를 `openat(O_PATH|O_NOFOLLOW)`로 고정하고 `/proc/self/fd`의
실제 경로가 예상 경로와 바이트 단위로 일치하면 요소별 open/close를 생략한다.
O_PATH이므로 FIFO나 장치를 일반 open하는 부작용이 없다. 최종 파일 종류,
파일시스템, 공유 namespace 세대 확인은 그대로 유지한다. 중간 심볼릭 링크가
다른 경로로 해석되거나 rename/delete·procfs 제한으로 경로 대조가 실패하면
기존 요소별 해석으로 돌아간다. 호출 사이에 FD나 경로를 캐시하지 않는다.
에뮬레이션 unlink는 신호 마스크 변경 전에 tracer 처리로 돌려 두 syscall을 줄였다.

[3회 교대 측정 원본](../tools/rootless/faststat/results/path-confirm-2026-09-20.json).
기존과 동일한 API 35 시험 환경이며 파일 1,000개, warm cache 조건이다.
현재 후보에는 openat2 차단을 적용했다. 이전 네이티브 방식은 이 제한 없이 실행했다.

| 작업 | 이전 네이티브(ms) | 현재 후보(ms) | 소요 시간 차이 |
| --- | ---: | ---: | ---: |
| 파일 정보 조회 | 13.111 | 15.477 | +18.0% |
| 작은 파일 읽기 | 34.771 | 45.987 | +32.3% |
| 생성·쓰기·이름 변경·삭제 | 204.243 | 261.065 | +27.8% |
| tar 압축 해제 | 514.713 | 589.840 | +14.6% |
| Python import | 441.425 | 442.893 | +0.3% |
| ls -ln | 130.128 | 93.272 | -28.3% |
| du | 47.004 | 68.906 | +46.6% |
| find -ls | 105.017 | 154.957 | +47.6% |

**이전 네이티브 하드링크 방식과 동등 이상이라는 목표는 미달성이다.**
파일 정보 조회 격차는 줄었으나 열린 FD의 링크 판별과 tracer 작업은 남아 있다.
삼성 Android 17 실측과 새 APK 공개도 아직 하지 않았다.
기존 shared-stat/open/create/close/path/cwd/rename/unlink/dpkg/statx 시험을
native·emulated 두 형식에서 통과했다. 중간 상대/절대 심볼릭 링크의 읽기·생성,
readlinkat 차단 시 fallback을 추가로 검사했다.

이번 추가 변경의 canonical 검사 `:android-app:assembleDebug
:android-runtime:testDebugUnitTest :image:test`는 통과했다(변경 없는 테스트는
Gradle UP-TO-DATE 재사용). APK 내부 엔진·loader·DSO 고정 해시도 일치한다.
앱 UI 단위 테스트와 새 배포판 설치는 이번 변경 범위에 없어 재실행하지 않았다.
Android MCP의 incremental APK 설치는 기기에서 거부되어 자동 streamed 설치로
성공했다. 회귀 검사 완료와 성능 측정은 이 설치된 시험 APK에서 확인했다.

## 디렉터리 FD 상대 경로 조회

`du`·`find`가 사용하는 dirfd 상대 stat/statx를 추가 가속했다. 디렉터리 FD를
새 O_PATH FD로 고정하고 실제 host 경로가 공유 바인딩에 유일하게 대응하며,
guest→host 재번역 결과도 일치할 때만 적용한다. 자식도 고정 후 실제 경로를
확인한다. 디렉터리 rename/교체, 중첩 또는 중복 바인딩, 링크, 삭제된 경로,
procfs 거부 등 불확실한 경우는 원래 PRoot 처리로 돌아간다. pathname/FD 캐시는 없다.
파일 바인딩으로 노출된 l2s backing 이름도 stat/statx 모두 기존 처리로 돌린다.

[5회 교대 측정 원본](../tools/rootless/faststat/results/dirfd-stat-2026-09-20.json).
API 35 ARM64 redroid, 파일 1,000개, warm cache이며 이번 후보만 openat2를 차단했다.

| 작업 | 이전 네이티브(ms) | 이번 후보(ms) | 소요 시간 차이 |
| --- | ---: | ---: | ---: |
| ls -ln | 130.193 | 95.493 | -26.7% |
| du | 45.902 | 18.381 | -60.0% |
| find -ls | 105.019 | 114.716 | +9.2% |

**전체 목표는 미달성이다.** du는 이전 네이티브보다 빨라졌으나 find와 파일 조회·읽기,
변경 작업의 잔여 격차는 남는다. 기존 파일 작업 수치는 앞선 경로 대조 시험을 참조하며
이번에 다시 측정한 것은 위 세 명령뿐이다. 삼성 Android 17 실측은 아니다.

새 `dirfd-stat-test.py`는 stat/statx 결과 전체를 원래 syscall과 비교하고,
일반 파일 가속 진입, 잘못된 FD/빈 경로, 심볼릭 링크, 중첩·중복 바인딩,
디렉터리 rename 후 같은 이름 교체, 하드링크, readlinkat/newfstatat/statx 거부를
두 저장 형식에서 검사했다. 기존 파일·FD·바인딩·패키지·statx 회귀 시험도 통과했다.
초기 시험 종료 시 generic rmtree가 노출된 l2s backing을 먼저 지워 EPERM이 발생했다.
이전 d0828fc 바이너리에서도 재현되어 기존 PRoot 제한으로 확인했고, fixture는 남은
하드링크 별칭을 명시적으로 삭제한 뒤 정리한다. 이 제한 자체를 해결한 것은 아니다.

이번 변경도 canonical `:android-app:assembleDebug :android-runtime:testDebugUnitTest
:image:test` 빌드를 통과했다. 변경 없는 단위 테스트는 Gradle UP-TO-DATE 결과를
재사용했다. APK 엔진/loader/DSO 고정 해시와 재현 소스 archive의 코드·시험·측정
일치를 확인했다. UI 변경이 없어 UI/새 배포판 설치 시험은 반복하지 않았다.
Android MCP를 사용했으며 incremental APK 설치 거부 뒤 streamed 설치가 성공했다.

## 기준 파일시스템 FD를 이용한 반복 검사 축소

tracer는 등록된 스레드마다 root 바인딩의 일반 파일시스템을 한 번 확인하고 O_PATH
FD를 보관한다. 이 참조가 살아 있는 동안 파일시스템 장치 번호가 재사용되지 않으므로,
DSO가 매번 새로 얻은 fstat의 장치 번호와 일치하면 별도 fstatfs를 생략할 수 있다.
다른 장치는 기존 fstatfs로 확인한다. 기준을 열 수 없거나 지원하지 않는 파일시스템이면
기존 방식이 유지된다. `PROOT_FASTSTORAGE=0`으로 이 최적화만 끌 수 있다.
파일 크기·시간·권한·링크 수·경로는 캐시하지 않는다. 열린 FD 앞뒤의 l2s 경로 확인도
유지한다. 기준 FD는 tracer 소유이며 상태 해제 시 게시를 취소하고 닫는다.
공유 ABI를 6으로 변경해 엔진·DSO를 함께 고정했다. 저장 형식 마이그레이션은 없다.

[3회 순서 순환 측정 원본](../tools/rootless/faststat/results/pinned-storage-2026-09-20.json).
같은 API 35 환경, 파일 1,000개, warm cache. 직전/현재 후보만 openat2를 차단했다.

| 작업 | 이전 네이티브(ms) | 직전 99031f9(ms) | 이번 후보(ms) |
| --- | ---: | ---: | ---: |
| 파일 정보 조회 | 13.432 | 15.519 | 14.695 |
| 작은 파일 읽기 | 34.088 | 45.143 | 44.771 |
| 생성·쓰기·이름 변경·삭제 | 207.149 | 254.981 | 259.391 |
| tar 압축 해제 | 512.717 | 579.891 | 581.400 |
| Python import | 437.449 | 440.958 | 440.605 |
| ls -ln | 129.768 | 92.343 | 92.915 |
| du | 46.960 | 18.538 | 17.836 |
| find -ls | 105.947 | 114.666 | 111.971 |

조회는 직전보다 5.31% 짧지만 이전 네이티브보다 9.4% 길다. 작은 파일 읽기·생성·삭제·
압축 해제·import의 추가 개선은 뚜렷하지 않아 주장하지 않는다. 전체 목표는 미달성이다.
숫자 PID로 `/proc/self/fd`를 치환한 별도 실험은 5회 결과가 일관되지 않았고 PID
namespace의 추가 검증도 필요해 채택하지 않았다. 실제 변경에는 포함하지 않는다.

두 저장 형식에서 기존 파일·FD·바인딩·패키지·statx/dirfd 검사를 통과했다.
`storage-test.py`는 가속 진입, procfs 바인딩 제외, 48개 스레드 생성·종료의 tracer FD
반납과 fork를 확인했다. 새 ABI로 빌드한 `fd-test`도 신호 처리/비지역 탈출, 8개 스레드,
fork ID, exec, FD 재사용, EFAULT, seccomp 검사를 통과했다.
처음에는 시험용 fcntl 후킹이 F_ADD_SEALS만 허용해 stdio DSO의 F_GETFL에서 실패했다.
후킹이 단순 flags 조회를 전달하도록 수정한 뒤 재시험에 통과했다.

`PROOT_FASTSTORAGE=0`의 기존 방식도 storage 회귀 시험에 통과했다.
canonical 앱 빌드/runtime/image 검사를 통과했고 변경 없는 단위 테스트는 Gradle의
UP-TO-DATE 결과를 재사용했다. APK 내부 엔진·DSO·loader 해시와 재현 소스 archive의
현재 코드/ABI/시험 일치를 확인했다. Android MCP로 검사했으며 UI/새 설치 시험은
이번 변경 범위에 없어 반복하지 않았다. 삼성 Android 17 실측과 공개 배포는 미실행이다.

## 변경 없는 경로 규칙의 재게시 제거

에뮬레이션 상태는 2인데 `publish_paths`가 bool로 받아 1과 비교하면서, 변경 없는
규칙도 매번 게시하던 문제를 수정했다. 모드 값 전체를 비교하고, 기존 namespace
세대·바인딩 포인터·모드가 바뀐 경우에만 갱신한다. 새 회귀 검사는 수정 전 엔진에서
`unchanged rules republished`로 실패하고 수정 후 두 저장 형식에서 통과했다.
mount/unmount/chroot·다른 스레드의 변경과 cwd/rename 갱신도 계속 통과한다.

[3회 순서 순환 측정 원본](../tools/rootless/faststat/results/path-generation-2026-09-20.json).
이전 엔진과 후보는 동일한 진단 함수 추가 DSO를 사용해 엔진 변경만 비교했다.
API 35 ARM64 redroid, 파일 1,000개, warm cache이며 에뮬레이션 양쪽은 openat2를 차단했다.

| 작업 | 이전 네이티브(ms) | 수정 전 엔진(ms) | 수정 후(ms) |
| --- | ---: | ---: | ---: |
| 파일 정보 조회 | 12.849 | 15.214 | 14.821 |
| 작은 파일 읽기 | 34.853 | 44.911 | 44.626 |
| 생성·쓰기·이름 변경·삭제 | 206.739 | 254.701 | 253.237 |
| tar 압축 해제 | 511.314 | 585.011 | 570.876 |
| Python import | 435.647 | 448.050 | 439.171 |
| ls -ln | 130.578 | 95.760 | 90.439 |
| du | 47.092 | 18.122 | 18.004 |
| find -ls | 105.751 | 113.453 | 112.959 |

tar·import는 세 번 모두 수정 전보다 짧았고 중앙값은 각각 2.42%, 1.98% 줄었다.
다른 작업은 회차별 변동이 있어 추가 개선을 일반화하지 않는다. 이전 네이티브보다
조회·읽기·변경·tar·find가 여전히 느리므로 전체 목표는 미달성이다.
동일 세대의 바인딩 문자열을 TLS에 저장하는 추가 후보도 시험했으나 뚜렷한 추가
이득이 없어서 약 17KiB/스레드의 메모리 부담과 함께 제외했다. 실험 원본은 위 JSON의
`rejected_snapshot_experiment`에 따로 표시했으며 최종 소스/바이너리에는 포함하지 않는다.

최종 DSO는 최초 수정 후보와 SHA-256이 같음을 확인했다. 기존 파일·FD·패키지·dirfd·
statx·storage·cwd 검사와 신호/비지역 탈출·fork/exec·FD 재사용 검사가 통과했다.
공유 ABI는 6을 유지하며 저장 형식 변경이나 마이그레이션은 없다.

최종 후보로 시험 APK를 복원한 뒤 규칙 회귀 검사를 재확인했다. canonical 앱 빌드와
runtime/image 검사를 통과했고 변경 없는 테스트는 UP-TO-DATE로 재사용했다.
APK 고정 해시와 소스 archive 일치도 통과했다. Android MCP의 incremental 설치는
기기 정책으로 거부되었으나 streamed 설치가 성공했다. UI/새 설치/삼성 Android 17
실측과 공개 배포는 이번에 실행하지 않았다.

## 빠른 close를 사용할 수 없는 경우의 조기 복귀

fork/스레드 생성으로 fast close가 비활성인 경우, signal mask를 바꾸기 전에 기존
libc close로 돌아가도록 분기를 옮겼다. 이 경우 close마다 보조 rt_sigprocmask 두 번을
생략한다. 사용 가능한 경우의 FD 종류 검사·신호 보호·실제 close와 errno는 유지한다.
실제 처리 시간을 별도로 재측정하지 않았으므로 추가 배속은 주장하지 않는다.

`close-fallback-test.c`는 syscall 금지 필터가 실제 작동하는 대조 자식을 먼저 검사하고,
다른 자식에서는 해당 호출 없이 close가 성공하고 FD가 EBADF가 되는지 검사한다.
수정 전 DSO에서는 실패하고 수정 후 native/emulated 모두 통과했다. 초기 SIGSYS
계수 방식은 PRoot의 ENOSYS 처리 때문에 제어 호출 검사에서 실패하여 폐기했다.
fastclose 기존 검사와 FD 신호/비지역 탈출·8개 스레드·fork/exec·FD 재사용·seccomp
검사도 통과했다. `faststat/build.sh`가 새 회귀 바이너리도 생성한다.

별도로 단독 FD 테이블에서 신호를 막고 fstat의 readlink 두 번을 한 번으로 줄이는
후보를 시험했다. 파일 3,000개 읽기 5회 비교 중앙값이 132.057→134.819ms로 늘어
채택하지 않았다. 원래 fstat 검사를 유지한다. [검사·실험 원본](../tools/rootless/faststat/results/close-fallback-2026-09-20.json).
전체 이전 네이티브 성능 목표는 미달성으로 유지한다.

canonical 앱 빌드/runtime/image 검사는 통과했다. 변경 없는 단위 테스트는
UP-TO-DATE로 재사용했다. APK의 고정 payload와 재현 archive의 현재 코드·시험 일치도
확인했다. Android MCP에서 검증했으며 incremental 설치 거부 후 streamed 설치가
성공했다. UI/새 설치/삼성 Android 17 실측 및 공개 배포는 반복하지 않았다.

## 삭제 경로 재읽기 제거 실험 — 미채택

`2aca6a8`을 기준으로, unlink/unlinkat 진입 처리 동안 PRoot가 방금 주입한
변환 경로를 재사용하는 후보를 시험했다. fake_id0/mountinfo/link2symlink 이외의
확장이 있으면 재사용하지 않으며 호출 종료 시 해제한다. 파일 상태·링크 수는
캐시하지 않는다. [실험 패치](rootless-experiments/unlink-path-reuse-2026-09-20.patch)는
기존 faststat/emulated-links/rename-local 패치 뒤에 적용한다. 생산 빌드에는 포함하지 않는다.

[측정 원본](rootless-experiments/unlink-path-reuse-2026-09-20.json).
API 35 ARM64 redroid 앱 UID에서 양쪽 모두 openat2를 차단하고, 같은 최신 DSO를
사용했다. 별도 진단 실행에서 파일 변경 1,000개 작업의 vm_read가
10,532→6,530회로 줄었지만 vm_write 3,429회와 진입/종료 정지 4,456/272회는 같았다.
진단을 끈 3회 교대 측정 중앙값은 253.560→255.888ms였다. 회차별 방향도 달라
실행 시간 개선은 입증되지 않았다. 따라서 후보를 채택하지 않고 원래 시험 APK로 복원했다.

후보 엔진의 ARM64 빌드와 unlink-entry 검사는 통과했다. 기존 에뮬레이션 및 후보의
에뮬레이션/네이티브 양쪽에서 열린 FD의 링크 수, 마지막 링크 삭제, dirfd, 심볼릭 링크,
디렉터리 fallback, 잘못된 포인터와 seccomp 동작을 검사했다. Android MCP를 사용했고
incremental 설치 거부 후 streamed 설치로 진행했다. 생산 소스·payload가 바뀌지 않아
canonical 앱 빌드는 반복하지 않았다. 삼성 Android 17 검증과 전체 성능 목표는 여전히
미완료다. 다음 후보는 삭제 경로를 게스트 메모리에 쓰는 단계까지 생략하는 것으로,
확장 콜백과 기존 커널 fallback에 올바른 경로를 전달할 수 있는지 먼저 확인해야 한다.
