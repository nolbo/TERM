# Direct Linux 호환성 목표 진행 상태

목표는 [감사의 C01–C45 전체](COMPATIBILITY_AUDIT.md)를 달성하는 것이다.
일부 fixture 통과나 syscall 수 증가를 전체 완료로 바꾸지 않는다.
이 문서는 구현 상태를 추적하며, 2026-09-25 감사 문서는 당시 관찰을 보존한다.

## 2026-09-25: 첫 구현과 검증

이전의 미커밋 thread 작업을 이어서 실제 kernel thread 실행을 검증했다.
공유 signal disposition은 lock으로 직렬화하고, signal mask·wait 상태와 guard가
있는 runtime signal stack은 thread별로 유지한다. TLS는 guest glibc가 준비한 값을
kernel clone에 전달한다. join/detach 후에는 실제 task 소멸을 확인해 runtime stack을
회수하며, clear-child-TID와 robust-list kernel 정리는 보존한다.

새로 고친 상태 오류:

- **C11:** DONTFORK/DOFORK 구간을 매핑 소유권과 함께 기록하고 fork 직후 자식에서
  사라진 구간을 제거한다. [Linux madvise](https://man7.org/linux/man-pages/man2/madvise.2.html)는
  오류가 나도 일부 구간을 변경할 수 있으므로, 오류 범위는 자식의 실제 매핑을 확인한다.
  이 확인은 새로운 runtime mmap 전에 수행한다. 부모에서 필요한 bookkeeping 용량을
  먼저 확인하여 자식에서 scratch mapping을 새로 만들지 않는다. 일반 madvise는
  기존 kernel 직행 경로를 유지한다.
- **C25:** private root/vfork/진행 중 파일 작업 FD를 제외한 범위를 kernel
  `close_range`로 닫거나 CLOEXEC로 표시한다. 추가 FD를 열지 않아 EMFILE에서도 동작한다.
  kernel에 close_range/CLOEXEC가 없는 경우에는 기존 proc 열거 경로가 남아 있으므로
  **구형 커널의 FD 고갈 조건까지 완료한 것은 아니다.**
- **C03/C05:** 메인 스레드가 먼저 `pthread_exit`하면 group leader의 mm를 대상으로 한
  pointer copy가 ESRCH로 실패했다. 현재 live TID를 사용하도록 수정했다. 내부 FD
  경로도 `/proc/<live-tid>/fd`로 바꿨다. 이는 leader 종료와 thread별 FD-table 분리를
  모두 다룬다. [proc FD의 leader 종료 제약](https://man7.org/linux/man-pages/man5/proc_pid_fd.5.html)과
  [직접 TID 경로](https://man7.org/linux/man-pages/man5/proc_pid_task.5.html)를 따른다.
  guest 전체 `/proc` 제공(C23)을 구현했다는 뜻은 아니다.

이 첫 단계에서는 multi-active-thread fork/exec, nonleader exec와 shared-VM spawn을 거부했다.
thread slot 128개, mapping 구간 2048개 등의 한도도 남아 있다. 부분적으로 실패한
inheritance hint의 최악 분할이 한도를 초과하면 fork 전 ENOMEM을 반환한다.
재조정 중 mincore까지 금지하는 플랫폼은 자식이 잘못된 소유권으로 계속 진행하지
않고 exit 126으로 실패한다. C01/C02/C05/C13에서 추가 개선·검증할 사항이다.

Unix socket은 live TID FD 별칭을 사용하므로 접두어 길이는 TID/FD 자릿수에 따라
달라진다. 긴 guest 이름 제한(C31)을 해소한 것은 아니다. 기본 FIFO peer의 thread
lock 교착은 제거했지만 signal interruption/cancellation(C14)은 여전히 남아 있다.

## 증거

[실행 결과와 fixture hash](results/compatibility-runtime-2026-09-25.json):
loader SHA-256 `98536095dedde3670177e227df42aea41b11023e010c955f5703976000e0f6c7`.
Android API 35 ARM64 redroid, 4 KiB, SELinux Disabled, initial Seccomp=0,
ordinary app UID, TracerPid=0, CapEff=0. **stock_policy_verified=false**.

- Native `build.sh`: `-Wall -Wextra -Werror`, freestanding runtime의 unresolved
  import/TLS 감사 통과.
- Host `memory-test`: 실제 fork에서 DONTFORK, DOFORK 복원, unmapped hole을 가로지르는
  부분 실패, WIPEONFORK 및 새 runtime mapping 보존 통과.
- Host `fds-host-test`: EMFILE에서 close/CLOEXEC, 축소한 RLIMIT 아래·위의 private FD
  보존 통과. `signals-exec-test`, `exec-metadata-test`도 통과.
- JDK 17 canonical Gradle 빌드/테스트 통과: runtime 180 passed/1 optional fixture
  skipped, image 4 passed. 추가 instrumentation APK 빌드·서명 검증 통과.
- Android instrumentation **13.125초, OK (1 test)**: 80회 top-level 실행, 두 엔진의
  PTY·network·signal·thread 시나리오. 의도한 거부·알려진 reference 실패도 포함한 숫자다.
  Direct 정적·동적 glibc의 fork inheritance/exec와 EMFILE close_range를 검사했다.
  pthread TLS/errno, 공유 파일·메모리, mutex/condition/barrier, targeted signal,
  200회 joinable/160회 detached 생성, robust owner death, native read cancellation,
  FD-table unshare, FIFO peer, leader 종료 후 file/Unix 작업 및 Python ThreadPoolExecutor를 검사했다.

실패 이력도 완료 증거에 포함한다:

1. 첫 thread 실행은 fixture에 glibc가 동적으로 여는 `libgcc_s.so.1`이 빠져 실패.
   fixture에 라이브러리와 package version을 추가하고 재검증했다.
2. Direct의 leader 종료 후 worker stat가 ESRCH로 실패. live TID 수정 후 위 확대
   검사에서 통과했다.
3. 같은 leader 종료 뒤 Unix getsockname은 Rootless에서 guest pathname을 반환하지
   않았다. 정적·동적 양쪽을 exit 78의 명시적인 limitation으로 다시 확인했다.
   Direct는 같은 검사에 정상 성공한다. reference 제한을 지원 성공으로 세지 않는다.
   기존 Rootless named datagram 실패와 Direct 100-byte Unix 이름 실패도 결과에 남아 있다.

Android MCP로 install/instrumentation/정책/결과를 확인했다. Context7은 Linux의
부분 madvise 실패 동작에 맞는 문서를 찾지 못해 Linux man-pages와 upstream kernel
소스로 보완했다. MCP 호출 오류는 없었으며 위 실패들은 실제 테스트 결과다.
Replicant와 codex_apps는 이번 runtime 구현에 UI/외부 앱 작업이 없어 사용하지 않았다.

재현 명령(기존 toolchain과 검증된 accelerated payload 사용):

```sh
work_dir=$(mktemp -d /tmp/direct-linux-compatibility.XXXXXX)
clang -O2 -Wall -Wextra -Werror tools/direct-linux/memory-test.c \
  tools/direct-linux/memory.c -o "$work_dir/memory-test"
"$work_dir/memory-test"
clang -O2 -Wall -Wextra -Werror tools/direct-linux/fds-host-test.c \
  tools/direct-linux/fds.c -o "$work_dir/fds-host-test"
"$work_dir/fds-host-test"
bash tools/direct-linux/build.sh "$work_dir/native"
python3 tools/direct-linux/fixtures.py "$work_dir/fixtures" --applications
./gradlew :android-app:assembleDebug :android-runtime:testDebugUnitTest :image:test \
  :android-runtime:assembleDebugAndroidTest \
  -ProotlessAcceleratedPayload=ABSOLUTE_VERIFIED_PAYLOAD_DIRECTORY \
  -ProotlessStdioBuffer=true --console=plain
```

[ELF packaging](ELF.md#reproduction)에 따라 APK를 만들고 Android MCP로 설치한 뒤:

```sh
am instrument -w \
  -e class dev.terminal.runtime.directlinux.DirectLinuxLoaderTest \
  -e directLinuxLoader true -e directLinuxThreads true \
  -e directLinuxSignalMasks true -e directLinuxNetwork true -e directLinuxPty true \
  -e directLinuxLoaderSha256 SHA256_FROM_BUILD \
  dev.terminal.runtime.test/androidx.test.runner.AndroidJUnitRunner
```

## 2026-09-25: C14 blocking과 신호 복귀

[구현·재현·제한 상세](BLOCKING_SIGNALS.md),
[최종 실행 결과](results/blocking-runtime-2026-09-25.json).
최종 guest open(생성·일반 파일 lease 포함), POSIX/OFD blocking lock, 번역된
network 호출은 guest mask로 대기한다. 신호 처리기는 guest PC/SP/GP context를
받고 kernel signal frame으로 진입한다. 별도 host 복귀 상태를 보관하여 정상
재시작과 명시적인 context 편집을 구분하며 pthread 취소의 unwinding도 보존한다.
중첩 임시 FD는 thread별로 보호하고, longjmp 후 다음 runtime 진입·thread 종료·
성공한 exec에서 회수한다.

정적·동적 glibc의 FIFO EINTR/SA_RESTART, 반복·중첩 longjmp, 중첩 FD close_range
보호, SIGINT/SIGTERM 처리/종료, 파일 lease·POSIX/OFD lock 중단, open/accept 취소와
pthread cleanup을 Android 및 ARM64 host에서 통과했다. Android 최종 묶음은
**25.747초, 142회 실행, OK (1 test)**이며 54개 비교 표본을 포함한다.
724개 fixture hash가 패키징 입력과 일치한다. Native 감사·서명·signal exec host
회귀·canonical Gradle 통과(runtime 180 pass/1 optional skip, image 4 pass).

같은 APK의 18회 교차 비교에서 shell+30 외부 명령 중앙값은 이전 Direct
35.603ms → 현재 36.829ms(**3.4% 비용 증가**), Rootless 47.698ms였다.
Rootless의 FIFO 재시작 ENOENT는 두 linking variant에서 별도 limitation으로
기록했다. 초기 test mask 누락과 설치 공간 부족, 진단 경로 실패 및 복구도 상세
문서에 남겼다. 여전히 stock enforcing/16 KiB 근거는 없다.

Linux vfork의 부모 신호 지연은 일반 I/O EINTR와 다른 계약이다. 부모 pipe wait를
임의로 unmask하지 않았으며 C04/C05에서 전체 spawn/vfork 의미와 함께 검증한다.
느린 준비 단계 파일 연산과 C17의 전체 확장 context/stop-continue 재시작까지
완료한 것은 아니다.

## 2026-09-25: C05 병행 exec

[병행 exec 설계·한계](CONCURRENT_EXEC.md),
[최종 결과](results/concurrent-exec-runtime-2026-09-25.json).
다중 thread, nonleader, leader 선종료 후 worker의 exec는 검증한 guest 상태를
봉인한 data FD로 전달하고 같은 APK 런타임에 kernel 재진입한다. 커널이 기존
스레드와 VM을 교체하며 PID/TID 규칙을 처리한다. freestanding 고정 주소 bootstrap은
같은 seccomp 필터를 상속하고 dispatcher를 복원한다. guest ELF의 kernel 실행은
없다. 런타임 ASLR 포기와 C01/C02 정책 gate를 설계 문서에 명시했다.

host/Android 정적·동적 6개 성공 경로, 200회 runtime 재진입, host의 EACCES 강제
거부 정적·동적 각 100회(soft NOFILE=16)를 통과했다. 최종 Android 27.570초,
2 tests/154회 실행/726 fixture hash 통과. canonical Gradle·native 감사·서명·ELF
metadata 회귀 통과. Rootless worker exec의 네 SIGSEGV는 limitation으로 기록한다.
동일 APK shell+30 명령 중앙값은 이전 38.166ms, 현재 37.169ms, Rootless 48.918ms.

다중 thread fork, shared-VM spawn/vfork, 모든 FD/FS table 조합과 전체 exec reset
계약은 남아 있다. 이번 단계는 C05의 exec 부분이며 전체 C05/C10 완료가 아니다.

## 전체 목표 추적

각 행의 원래 완료 조건은 감사 문서의 같은 ID를 따른다. 현 기기의 통과를
stock enforcing/다른 페이지 크기/모든 앱에 일반화하지 않는다.

| ID | 목표 | 현재 상태와 남은 증거 |
| --- | --- | --- |
| C01 | stock Android 정책 | 미완료: enforcing 기기 필요. |
| C02 | 페이지·커널·Bionic 차이 | 미완료: 16 KiB, 지원 버전·커널 matrix. |
| C03 | pthread | 부분 구현·실행 검증: 기본 pthread/Python 통과. clone3/rseq/PI·추가 cancellation/CPU migration 남음. |
| C04 | spawn/vfork | 미완료: shared-VM spawn, 전체 vfork 의미. |
| C05 | thread 수명·병행 exec | 부분 구현·실행 검증: 다중 thread/nonleader/leader 선종료 exec와 kernel 거부 복원 추가. 다중 thread fork·전체 FD/FS 조합 남음. |
| C06 | execveat/fexecve | 미완료. |
| C07 | argv/env 한도 | 미완료. |
| C08 | ELF 허용 범위 | 미완료: 큰 ELF/다양한 linker/CPU property. |
| C09 | 실행 권한·동시 쓰기 | 미완료: 가상 mode와 ETXTBSY 수명. |
| C10 | exec 전체 상태 | 병행 exec의 kernel 교체와 precommit/커널 거부 복원 검증. 전체 reset 계약·postcommit 실패 matrix는 미완료. |
| C11 | DONTFORK 소유권 | 현 구현 수정·host/Android 검증 완료. C01/C02의 다른 플랫폼 gate는 별도 미완료. |
| C12 | 메모리 API | 미완료: mremap/msync/memfd 등. |
| C13 | 자원 한도 | 미완료: thread stack 1 MiB로 확장했지만 고정 한도와 RLIMIT 계약 남음. |
| C14 | blocking과 신호 | open/lease·FD lock·network의 EINTR/restart/cancellation/nonlocal FD cleanup 구현·host/Android 검증. 준비 단계의 느린 파일 연산과 C04/C05 vfork 계약은 미완료. |
| C15 | guest altstack | 미완료. |
| C16 | guest SIGSYS | 미완료. |
| C17 | signal context/restart | GP PC/SP/결과 노출·편집, 취소 unwind와 재시작 일부 검증. SVE/SME·전체 setcontext/manual frame·stop/continue·restart_syscall은 미완료. |
| C18 | timer·queued signal | 미완료. |
| C19 | identity·소유권 | 미완료. |
| C20 | hardlink/FIFO/node | 미완료: FIFO 검사는 host가 fixture를 준비한 것이며 guest mkfifo 구현이 아님. |
| C21 | 최신 pathname API | 미완료. |
| C22 | ACL/xattr/경쟁 | 미완료. |
| C23 | guest proc | 미완료: live-TID 경로는 내부 구현만 변경. |
| C24 | devpts/중첩 PTY | 미완료. |
| C25 | FD 고갈 복구 | 현 kernel 수정·host/Android 검증. close_range 미지원 구형 kernel의 EMFILE/ENFILE fallback 남음. |
| C26 | private FD·한도 | 일부 진행: shared-table 보호와 native range 분할. 모든 FD 전달·분리 조합은 미완료. |
| C27 | lock/고급 I/O | 미완료. |
| C28 | 파일 감시 | 미완료. |
| C29 | process 정보/제어 | 미완료. |
| C30 | 추가 IPC/비동기 ABI | 미완료: 실제 app 요구와 플랫폼별 분류. |
| C31 | 긴 Unix pathname | 미완료: 100-byte 이름 제한 재확인. |
| C32 | abstract/autobind/batch | 미완료. |
| C33 | Unix 수명·credentials | 일부 진행: leader 종료 뒤 이름 확인. 경쟁/credentials/ancillary 실패는 미완료. |
| C34 | socket family | 미완료: 허용 가능한 netlink 등 범위 결정·실측. |
| C35 | 실제 DNS/NSS | 미완료. |
| C36 | 외부 통신/CA | 미완료. |
| C37 | Debian/apt/dpkg | 미완료. |
| C38 | storage 의미/노출 | 미완료. |
| C39 | 사용자 환경 | 미완료. |
| C40 | 대화형 앱 | 미완료: 기존 host 제공 PTY 회귀는 통과. |
| C41 | 개발 workload | 일부 진행: Python thread pool 추가. 빌드·다른 언어·장시간 부하는 미완료. |
| C42 | backend/session | 미완료. |
| C43 | broker/격리 계약 | 미완료. |
| C44 | kernel 기능 범위 | 미완료: syscall별 가능/제한/플랫폼 불가 분류와 증거 필요. |
| C45 | 회귀/비교 기준 | 진행: thread/매핑/FD 회귀와 reference 실패 기록 추가. 전체 matrix 미완료. |

다음 구현은 C04/C05/C26의 spawn·다중 thread fork·FD-table 소유권과 남은 C14/C17 동작으로
이어간다. 전체 목표는 계속 진행 중이다.
