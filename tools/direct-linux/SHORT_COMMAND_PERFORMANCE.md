# Direct Linux 짧은 명령 실행 비용 개선

2026-09-25. `/bin/true` 30회처럼 인자가 적고 곧바로 끝나는 명령의 실행 경로를
최적화했다. 같은 테스트 APK 안에서 이전 로더·새 로더·기존 가속 Rootless를
교차 실행한 **54쌍의 쌍별 시간 감소율 중앙값은 2.3%**였다. 54쌍 중 48쌍에서
새 로더가 빨랐다. 개선 폭은 작으며 전체 Linux 성능 우위를 뜻하지 않는다.
이전 [인자 복사 개선](ARGUMENT_PERFORMANCE.md) 이후 커밋 `5672bda`를 기준으로 했다.

## 병목과 변경

짧은 명령은 사용자 공간의 exec 준비 및 libc 시작 비용에 민감하다.
다음 세 경로에서 이미 불필요해진 작업을 줄였다.

- `guest.c`: 기존 dispatcher가 인자·결과를 바꾸지 않고 커널에 전달하던
  mprotect/madvise/mincore, PID·UID 조회, set_tid_address/set_robust_list,
  getrandom, prlimit64, uname/getrusage를 seccomp의 기존 직접 전달 목록에 추가했다.
  libc 시작 때의 불필요한 SIGSYS 진입·복귀를 피한다. mmap 소유권, 파일·FD,
  신호 마스크, clone/exec 처리는 계속 각각의 번역 경로를 거친다. 커널/Android의
  기존 syscall·매핑·권한 제한은 그대로 적용된다. getrandom의 잠재적 대기도
  게스트의 실제 신호 마스크 아래에서 이루어진다.
- `signals.c`, `exec.c`: 매 exec마다 61개 신호 action을 모두 조회하던 루프를
  바꿨다. 첫 이미지 교체는 물려받은 Bionic action을 모두 검사한다. 이후에는
  실제로 처리기를 설치한 신호만 다시 조회한다. 설치 성공 후 이전 action을
  반환하다 EFAULT가 난 경우도 추적한다. IGN은 유지하고 처리기는 초기화하며,
  KILL/STOP과 내부 SYS 신호는 건드리지 않는다. one-shot 처리기가 이미 기본값으로
  돌아간 경우에는 불필요한 조회 한 번만 남는다. fork는 이 상태를 함께 복사한다.
- `files.c`: 경로 중간 요소를 `O_PATH|O_DIRECTORY|O_NOFOLLOW`로 열어 성공한 경우,
  디렉터리인지 다시 fstat하지 않는다. 심볼릭 링크의 ENOTDIR은 기존의 고정된
  링크 FD를 통한 해석 경로로 처리한다. 루트 상대 절대 링크, `..`, device 처리와
  마지막 요소의 기존 동작을 유지한다. 디렉터리·inode의 영속 캐시는 추가하지 않았다.

별도 프로파일에서 신호·FD 초기화 구간의 명령당 평균은
**39.691→27.449 µs**였다. 모든 구간이 빨라진 것은 아니다. ELF 읽기·검증은
83.394→95.378 µs였으며, 매핑 해제 등 수정하지 않은 구간도 변동했다.
각 변경의 개별 처리 시간 기여도를 분리 측정한 결과로 해석하지 않는다.

## 교차 측정

초기에는 APK를 교체하며 기존 7쌍 benchmark를 두 번 비교했다.
짧은 명령은 49.193→46.241 ms와 46.958→46.442 ms로 개선 폭이 6.0%와 1.1% 사이였다.
이 차이를 그대로 대표값으로 삼지 않고 동일 APK의 비교를 추가했다.

`DirectLinuxLoaderTest`의 `directLinuxBaselineSha256` 옵션은 별도 비교 APK의
기존 `direct-linux-probe` 테스트 슬롯에 담긴 이전 로더를 사용한다. 두 로더 모두
기존 중앙 APK 실행기 resolver를 거치고 실제 파일 SHA-256을 검사한다. 제품의
실행기 허용 목록이나 앱 데이터 실행 정책을 바꾸지 않았다. 이 APK는 고정 파일
transport probe 검사가 아니라 로더 비교를 위해 만든 테스트 산출물이다.

각 차수에서 이전/새 로더/Rootless의 여섯 가지 실행 순서를 골고루 사용한
18개 세트를 실행했다. 세 차수는 동일한 716개 fixture와 동일 Rootless 바이너리를
사용했다. 게스트 CLOCK_MONOTONIC으로 셸 생성부터 외부 `/bin/true` 30회와 종료
대기까지 측정한다. Android 바깥 실행기의 시작·관찰 지연은 포함하지 않는다.
프로파일·추적은 시간 측정 중 껐으며 코드·파일은 warm 상태다.

| 차수 | 이전 로더 중앙값 | 새 로더 중앙값 | Rootless 중앙값 | 쌍별 감소율 중앙값 |
| --- | ---: | ---: | ---: | ---: |
| 1 | 48.017 ms | 47.398 ms | 49.445 ms | 1.1% |
| 2 | 48.891 ms | 47.317 ms | 49.150 ms | 3.0% |
| 3 | 48.613 ms | 47.152 ms | 48.859 ms | 2.5% |

마지막 열은 같은 세트의 `1 - 새 시간 / 이전 시간`을 계산한 뒤 중앙값을 구한다.
서로 다른 열의 중앙값을 나눈 값과 같지는 않다. 전체 54쌍의 중앙값은 2.3%다.
[원시 표본](results/short-command-performance-2026-09-25.json)은 초기 APK 교체 비교와
프로파일도 보존한다. 최종 교차 비교용 fixture에는 반복 exec의 신호 초기화 및
경로 오류 검사를 추가한 네 개의 process/files 바이너리가 포함된다. 같은 APK의
전후 비교끼리는 모든 입력이 동일하다.

기존 넓은 작업 측정도 통과했다. Python/Git 전체는 552.203→552.870 ms와
551.958→539.811 ms로 일관된 추가 개선을 입증하지 못했다. Rootless의 기존 Git
정리 실패 때문에 전체 앱의 Rootless 비교는 명시적으로 제외했다. CPU·메모리,
cold cache, 물리 기기의 열·전원 조건은 평가하지 않았다. 매핑 해제와 ELF 준비
비용은 여전히 남아 있으며, 큰 구조적 가속을 달성했다고 주장하지 않는다.

## 검증과 재현

호스트 및 빌드 검증:

```sh
clang -O2 -Wall -Wextra -Werror tools/direct-linux/signals-exec-test.c \
  -o /tmp/direct-linux-signals-exec-test
/tmp/direct-linux-signals-exec-test
clang -O2 -Wall -Wextra -Werror tools/direct-linux/exec-metadata-test.c \
  tools/direct-linux/memory.c tools/direct-linux/copy.S \
  -o /tmp/direct-linux-exec-metadata-test
/tmp/direct-linux-exec-metadata-test
```

신호 테스트는 실제 ARM64 커널 action을 조회한다. 상속된 처리기·IGN·fork 복사,
설치 후 반환 오류, 실패한 설치, 재조회 횟수를 검사한다. one-shot의 커널 초기화
상태는 이 호스트 검사에서 명시적으로 만들어 검사하며 실제 신호 전달 검사는
기존 Android 신호 fixture가 담당한다. exec 메타데이터·인자 경계 검사도 통과했다.
`process-test.c`는 30회 반복 exec에서 새 처리기의 초기화와 IGN 보존을 확인하고,
`files-test.c`는 일반 파일 및 절대 링크가 경로 중간에 올 때의 ENOTDIR를 확인한다.

네이티브 빌드의 경고·미해결 심볼·TLS 감사와 APK 서명 검사가 통과했다.
JDK 17에서 저장소 표준 `:android-app:assembleDebug :android-runtime:testDebugUnitTest
:image:test` 및 `:android-runtime:assembleDebugAndroidTest`가 성공했다. 비교 옵션 추가
뒤 테스트 APK도 다시 빌드했다. 변경 없는 단위 테스트는 UP-TO-DATE 결과를
재사용했다(기존 runtime 180 통과/1 선택 fixture skip, image 4 통과).

[ELF 문서](ELF.md#reproduction)의 준비·서명 절차를 사용하되, 비교 APK를 만들 때
`package-probe.py`의 probe 인자에 이전 로더 파일을 전달한다. 예:

```sh
python3 tools/direct-linux/package-probe.py TEST_APK BASELINE_LOADER COMPARISON_UNSIGNED_APK \
  --loader NEW_LOADER --fixtures FIXTURE_DIRECTORY
```

서명·설치 뒤 instrumentation에 `directLinuxLoader=true`,
`directLinuxBaselineSha256=8997164fcf180ce540d671898d5c9eced3a7b70748f7e6704e828d1fe8f463be`,
`directLinuxLoaderSha256=45de016fc03e7902a65f01d3fb6b6fa6e35576d37671e57a3186fe48bc090ec9`를
전달한다. 넓은 작업 측정은 별도로 기존 `directLinuxBenchmark=true`와
`directLinuxSkipApplicationReference=true`를 쓴다.

Android MCP로 일반 앱 UID에서 세 교차 측정 및 최종 신호·네트워크·DNS/TLS·Unix
socket·PTY 회귀를 실행했다. [최종 결과](results/short-command-runtime-2026-09-25.json)는
118개 실행(64개 회귀 실행 + 54개 비교 실행), 두 PTY 시나리오를 기록하며
15.967초에 통과했다. 모든 MCP 호출이 성공했다. 기존 API만 사용해 Context7,
UI 변경이 없어 Replicant, 외부 앱 작업이 없어 codex_apps는 사용하지 않았다.
privileged AVF 검사는 대상이 아니다.

대상은 ARM64 API 35 redroid, SELinux Disabled, 초기 Seccomp=0/CapEff=0/TracerPid=0이다.
stock enforcing Android의 정책 호환성은 미검증이다. Direct의 긴 Unix 경로와
Rootless의 named datagram 실패는 기존 한계로 계속 기록한다. 제품의 Rootless,
UI 및 세션은 변경하지 않았다. 작업 시작 시 있던 미완성 스레드 변경은 별도로
보존했으며 비교 로더나 이번 커밋에 포함하지 않았다. 전체 스레드·신호·파일시스템
호환성과 제품 통합 과제는 남아 있다.
