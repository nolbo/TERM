# Direct Linux: 병행 exec와 APK 런타임 재진입

2026-09-25. 여러 pthread가 살아 있거나 group leader가 먼저 종료한 상태에서도
guest `execve`를 실행한다. 정적·동적 glibc의 leader/worker/leader 선종료 worker
경로를 검증했다. **다중 thread fork와 shared-VM spawn은 아직 미완료**다.

## 문제와 선택

한 스레드의 빠른 exec는 기존 guest 매핑만 교체한다. 다른 스레드가 실행 중이면
그 코드를 먼저 unmap할 수 없고, nonleader의 exec에는 kernel task identity 변경도
필요하다. 이전 구현은 이 경우 EOPNOTSUPP를 반환했다.

병행 exec는 검증을 마친 뒤 APK에 패키징된 **동일 런타임**에 실제 kernel exec를
수행한다. 커널이 기존 스레드 종료, 주소 공간 교체, FD-table 분리, nonleader의
leader 전환을 담당한다. guest ELF는 계속 독립 root의 파일을 검증·매핑하며,
app-data ELF 또는 memfd를 kernel 실행 파일로 사용하지 않는다. 중앙
`ApkNativeExecutableLoader`를 통한 초기 실행 정책도 유지한다.

[seccomp 필터는 exec 뒤에도 유지된다](https://man7.org/linux/man-pages/man2/seccomp.2.html).
하지만 signal handler는 reset되므로, 재진입 전에 Bionic 동적 링커가 임의 주소에서
syscall을 실행하면 SIGSYS를 처리하지 못한다. 따라서 loader를 interpreter·libc·TLS
import가 없는 freestanding ET_EXEC로 바꾸었다. 이미지 주소는 `0x2000000000`이며
39-bit VA 안에 들어간다. 모든 bootstrap syscall은 이전과 같은 허용 PC를 통과한다.
재진입 때 dispatcher/guarded signal stack만 복원하고 필터를 추가로 쌓지 않는다.

**trade-off:** 런타임 자체의 ASLR을 포기한다. guest PIE·shared library·stack의
주소 무작위화와 W^X는 유지하고, dynamic linker 대신 bootstrap이 자체 RELRO를
mprotect한다. 이 구조는 악성 guest 격리 경계가 아니며 stock Android에서 허용된다는
증거도 아니다. 배포 정책/SELinux 검증은 C01/C02/C43의 별도 gate로 남는다.

전체 동적 링커 구간을 filter에서 허용하거나 exec 전에 필터를 제거하는 방법은
사용하지 않았다. 각 exec마다 필터를 추가하면 횟수에 따라 kernel 한도에 도달한다.
기존 한 스레드 in-process 경로는 짧은 명령의 반복 실행 비용을 유지하기 위해 남겼다.

## 상태 전달과 실패

1. 기존 VM에서 인자·환경·shebang과 main/interpreter ELF를 검증한다. 진행 중인
   source FD는 다른 스레드의 close/dup/close_range로부터 보호한다.
2. root FD, 검증 메타데이터, 인자·환경의 상대 offset, guest signal mask를 data-only
   memfd에 기록하고 WRITE/GROW/SHRINK/SEAL로 봉인한다. 필요한 네 FD만 임시로
   CLOEXEC를 해제한다. 파일/스레드 lock은 kernel exec 직전에 해제한다.
3. `/proc/<live-tid>/exe`로 APK 런타임을 다시 실행한다. `/proc/self/exe`에 의존하면
   이미 종료한 group leader 때문에 실패하므로 live TID를 사용한다.
4. 새 VM에서 봉인·길이·offset·FD 관계를 검사하고 같은 pinned inode의 ELF를 다시
   검증한다. 이전 VM 주소를 역참조하지 않는다. kernel auxv에서 새 vDSO/HWCAP/page를
   읽고 guest 이미지·스택·TLS 초기 상태와 dispatcher를 준비한다.

kernel exec 자체가 실패하면 FD flags를 복원하고 준비용 FD/메모리를 회수하여
원래 guest로 errno를 돌려준다. 검증 단계 실패도 기존 이미지·스레드를 보존한다.
반면 kernel commit 뒤의 매핑/메타데이터 변경/복원 실패는 이전 VM으로 돌아갈 수
없으므로 명시적인 메시지와 exit 126으로 끝난다. 전체 exec 상태 계약(C10), ETXTBSY
수명(C09), 모든 FD-table 조합(C26)이 완료됐다는 뜻은 아니다.

초기 loader에는 더 이상 Bionic/TLS/allocator의 매핑이 없다. 원래 kernel argv/auxv
stack에만 DONTFORK를 적용한다. 이전 `bootstrap.h`/`bootstrap-test.c`는 기존 Bionic
생략 정책의 역사적 회귀 자료이며 현 loader가 호출하지 않는다.

`EXEC_PROFILE`의 `replacing=2`는 새 VM에서의 재검증·매핑 비용이다. 기존 VM의
준비/serialization과 kernel 전환 시간은 포함하지 않는다. 이전 in-process 구간과
그 합계를 직접 비교하면 안 된다. 전체 latency는 외부 측정을 사용한다.

## 검증과 한계

[최종 실행 기록](results/concurrent-exec-runtime-2026-09-25.json): loader SHA-256
`9b9d268ce44506569f791a430b68c63eb7f851113ea121d54824940a28ba104b`.
전체 Android 검사는 **27.570초, OK (2 tests)**, 154회 top-level 실행(54개 비교 표본
포함), 726개 fixture hash를 검증했다. 의도한 거부/비교 엔진 제한도 포함한 실행 수다.
Native warnings-as-errors/import/TLS/interpreter/relocation 감사, host ELF metadata
회귀, APK 서명, JDK 17 canonical Gradle과 instrumentation 빌드가 통과했다.
runtime unit 180 pass/1 optional fixture skip, image 4 pass다.

같은 APK의 `f20fd01`과 18회씩 순서를 바꾼 shell+30 명령 비교 중앙값은 이전
**38.166ms → 37.169ms**, Rootless **48.918ms**였다. 쌍별 시간 감소율 중앙값은
**2.7%**, 18쌍 중 13쌍이 감소했다. 이는 warm 단일-thread 명령 검사이며 병행 exec의
속도 또는 일반 애플리케이션 성능을 대표하지 않는다.

ARM64 host와 Android API 35 ARM64 redroid에서 확인했다. Android는 4 KiB,
SELinux Disabled, initial Seccomp=0/CapEff=0/TracerPid=0인 일반 앱 UID다.
**stock_policy_verified=false**이며 enforcing/16 KiB 기기는 아직 검증하지 않았다.

- 동일 필터를 상속하며 APK runtime kernel exec **200회** 후 dispatcher 정상 작동.
- leader/worker/leader 선종료 후 worker의 exec: 기존 PID, 새 leader TID, 형제 task
  제거, 이전 매핑 소멸, TLS 초기화, 인자·환경·cwd·FD offset·CLOEXEC, caught/ignored
  disposition 및 SIGSYS를 포함한 논리 mask 검사. 형제는 FIFO 대기와 stat/mmap을
  동시에 수행한다. 잘못된 경로의 precommit ENOENT 후 형제 실행도 계속된다.
- 호스트 fault injection은 초기 loader exec만 허용하고 재진입은 EACCES로 거부한다.
  정적·동적 각각 **100회** 반복하며 RLIMIT_NOFILE soft=16에서도 FD 누출 없이 기존
  스레드·FD flags·signal disposition을 보존한다. 이 검사는 Android 미실행이다.
- Rootless leader exec는 통과한다. worker 및 leader 선종료 worker exec는 정적·동적
  모두 SIGSEGV가 재현됐다. tracer 자체 exit=0이어도 지원 성공으로 세지 않는다.
  알려진 네 경우만 limitation으로 기록하고 다른 실패는 회귀를 실패시킨다.
- 기존 pthread, blocking signal/cancellation, ELF/파일/FD, Python/Git, network와
  PTY/job-control 회귀를 함께 실행한다. 새 구현은 제품 payload/backend에 미통합이다.

초기 비교 fixture가 Rootless 동적 이미지의 기존 매핑 주소와 충돌(EEXIST)했다.
MAP_FIXED_NOREPLACE로 빈 후보 주소를 선택하고 그 주소를 exec 인자로 전달하도록
고쳤다. 무조건 MAP_FIXED로 reference 메모리를 덮어쓰지 않는다.

## 재현

[ELF packaging](ELF.md#reproduction)과 `fixtures.py --applications`를 사용한다.
같은 APK의 probe slot에 `f20fd01` loader를 넣으면 이전 구현과 교대 측정할 수 있다.

```sh
clang -O2 -Wall -Wextra -Werror tools/direct-linux/exec-denial-host.c \
  -o /tmp/direct-linux-exec-denial
# fixture ROOT_DIRECTORY에는 input 및 tmp/thread-fifo를 준비한다.
/tmp/direct-linux-exec-denial LOADER ROOT_DIRECTORY /bin/concurrency-static exec-denied
/tmp/direct-linux-exec-denial LOADER ROOT_DIRECTORY /bin/concurrency-dynamic exec-denied

am instrument -w \
  -e class dev.terminal.runtime.directlinux.DirectLinuxExecTransitionTest,dev.terminal.runtime.directlinux.DirectLinuxLoaderTest \
  -e directLinuxReexec true -e directLinuxLoader true -e directLinuxThreads true \
  -e directLinuxSignalMasks true -e directLinuxNetwork true -e directLinuxPty true \
  -e directLinuxLoaderSha256 SHA256_FROM_BUILD \
  dev.terminal.runtime.test/androidx.test.runner.AndroidJUnitRunner
```

Android MCP를 설치·실행·결과 수집에 사용했다. 이번 단계의 MCP 호출 오류는 없었다.
Context7의 이전 Linux 자료 부족은 upstream kernel/man-pages로 보완했다.
UI/외부 앱 변경이 없어 Replicant/codex_apps는 사용하지 않았다.
