# Direct Linux: 다중 스레드 fork와 FD-table 소유권

2026-09-25. 다른 pthread가 FIFO에서 대기하거나 파일·메모리를 사용하는 중에도
leader/worker/leader 선종료 worker가 fork할 수 있다. 각 자식은 자신의 스레드를
만들고 join한 뒤 exec할 수 있다. FD-table을 공유하지 않는 thread clone 및
신호 처리기의 close_range(UNSHARE)도 table별 내부 FD 소유권을 유지한다.

## 변경

`dl_fork_begin/end`는 thread → files → actions → memory 순서로 runtime 상태를
고정한다. 실제 kernel COW fork 이후 자식에서는 호출자 이외의 runtime stack과
thread record를 제거하고, 자식 FD-table에 불필요하게 복사된 다른 스레드의 I/O
pin을 닫는다. guest 메모리·스레드 스택은 kernel fork와 libc의 관리를 그대로 따른다.
부모의 스레드·FD·락은 유지하며 자식의 독립 lock은 다시 사용할 수 있다.

[CLONE_FILES](https://man7.org/linux/man-pages/man2/clone.2.html)는 파일 descriptor
table의 공유를 정한다. 같은 thread group의 FD 번호만 비교하면 분리된 table에서
같은 번호의 정상 guest FD를 잘못 보호하거나 닫을 수 있다. 각 runtime thread에
실제 table identity를 부여하고, 내부 FD 보호/복사/폐기를 해당 identity에 한정한다.
CLONE_FILES 없는 clone은 copied pin 목록을 kernel clone 직전에 고정하고,
자식의 guest 진입 전에 그 복사본을 닫는다. 실패하면 부모 FD를 건드리지 않는다.

신호 처리기는 진행 중인 runtime I/O pin을 가진 채로 table을 분리할 수 있다.
이때 자기 pin은 새 table에 남겨야 원래 대기를 재시작할 수 있지만, 기존 table의
동일 pin 복사본은 더 이상 쓸 주체가 없다. 이 경우에만 짧은 runtime cleanup
thread를 기존 table에 남겨 둔다. 분리가 성공하면 그 thread가 불필요한 기존 pin을
닫고, 실패하면 아무 FD도 닫지 않는다. 호출자는 kernel clear-child-TID까지 기다린
후 임시 guarded stack을 회수한다. guest 코드/TLS/handler를 실행하지 않으며 모든
신호가 차단된 runtime 내부 작업이다. 자원 할당 실패는 table 변경 전에 반환한다.

clear-child-TID의 shared futex wake는
[upstream kernel `mm_release`](https://raw.githubusercontent.com/torvalds/linux/v5.10/kernel/fork.c)를
따른다. 이를 FUTEX_WAIT_PRIVATE와 짝지으면 영원히 대기할 수 있으므로 shared
FUTEX_WAIT로 기다린다. 일반 runtime lock/stage 알림은 같은 VM의 private futex다.

기존 COW vfork 호환 경로의 pipe도 부모에서 private I/O record로 보호한다.
자식 writer는 별도 runtime FD 소유권으로 옮기고 부모 reader는 대기 후 닫는다.
**실제 shared-VM vfork/spawn 의미를 구현한 것은 아니다.** C04는 계속 미완료다.

## 검사

[최종 원시 결과](results/fork-fd-runtime-2026-09-25.json): loader SHA-256
`0458431dcffb00e4440d4ca0600164022e2437e63d2c9e856c85cb0f776441f3`.
Android **32.051초, OK (2 tests)**, 174회 top-level 실행, 728개 fixture hash 확인.
의도한 거부와 reference limitation도 실행 수에 포함된다. Native 감사/경고 검사,
APK 서명, canonical Gradle 및 최종 instrumentation 빌드를 통과했다.
runtime unit 180 pass/1 optional skip, image 4 pass다. 54개 비교 표본을 포함하며 같은
APK에서 `f20fd01`/현재/Rootless를 18회씩 교대했다. shell+30 외부 명령 중앙값은
각각 **37.077/36.851/48.851ms**였다. 이전 Direct 대비 쌍별 감소율 중앙값은 0.97%,
13/18쌍이 감소했다. 이 작은 차이를 뚜렷한 성능 향상이나 전체 앱 우위로 해석하지 않는다.

정적·동적 glibc 각각 아래를 ARM64 host와 Android에서 검사한다.

- leader/worker/leader 선종료 worker의 fork 각 30회: 자식의 내부 FD 회수,
  새 pthread 생성/join/exec, 부모 worker의 계속된 stat/mmap.
- CLONE_FILES 없는 kernel thread 160회: 다른 스레드가 FIFO open pin을 유지하는
  동안 자식 table의 여유 FD가 복구되는지와 부모 guest FD 보존 확인.
- FIFO 대기 신호 처리기에서 table 분리 및 SA_RESTART: 기존 table과 새 table의
  pin 회수, 같은 FD 번호의 독립 사용, 부모 FD를 닫지 않고 worker FD만 닫기.

soft RLIMIT_NOFILE=16에서 `dup`으로 실제 남은 FD 슬롯을 세므로 private FD를
EBADF로 감추는 것만으로 통과하지 않는다. kernel 단위 EMFILE/close_range와
DONTFORK/DOFORK/WIPEONFORK/부분 madvise 오류 회귀도 통과했다.

초기 Android FIFO 시험은 fixture가 상속받은 SIGUSR1 mask 때문에 timeout이었다.
진단에서 reader의 SigPnd=0x200, SigBlk=0x1204를 확인했다. fixture가 명시적으로
mask를 비우도록 수정했으며 런타임의 상속 의미는 유지했다. 첫 `/proc` 진단은
timeout 정리 이후라 No such file이었고, 다음 실행에서 정리 전에 상태를 수집했다.

Rootless는 정적 fork 3종과 두 linking variant의 비공유 table clone을 통과했다.
동적 fork의 leader/worker에서는 자식의 가용 FD가 13개 대신 12개였다. 남은 FD가
O_PATH이며 작업 중이던 `/input`과 같은 device/inode임을 확인했다. reference 모드는
이 조건에서만 exit 78/LIMIT를 기록하며 일반 FD 오류를 허용하지 않는다. 마지막
실행의 동적 leader 선종료 fork는 통과했지만 이 경쟁의 부재를 일반화하지 않는다.

table 분리 뒤 FIFO 재시작은 정적·동적 모두 ENOENT로 실패한다.
별도 reference 모드가 정확히 이 조건을
exit 78/명시적인 LIMIT로 기록한다. Direct는 정상 FIFO 전달을 요구한다. 기존
Rootless nonleader exec SIGSEGV 및 Unix pathname 제한도 그대로 별도 기록한다.
Unix pathname reference 검사는 재실행 중 정상 성공도 관찰됐다. 실패만 기대하던
조건을 고쳐 정상 PASS와 정확한 LIMIT/exit 78을 모두 구분하며, 보고서의 검증 여부는
실제 두 linking variant 결과로 계산한다. FIFO reference도 정상 성공을 배제하지 않는다.

## 범위와 재현

기존 [병행 exec](CONCURRENT_EXEC.md)의 fixed APK bootstrap 위에서 구현했다.
table identity는 runtime이 지원하는 clone/close_range 경로에만 적용된다.
guest unshare 전체, CLONE_VM 별도 process, SCM_RIGHTS/모든 FD 전달 조합,
추가 FS 공유 조합, 자원/커널 matrix는 C04/C05/C26에 남아 있다. 기존 thread 한도
128도 유지한다. FD 한도 16 검사는 startup 후이며 초기 root FD 256 요구를 없애지 않는다.

[ELF packaging](ELF.md#reproduction)과 `fixtures.py --applications`로 APK를 만들고,
[병행 exec 실행 명령](CONCURRENT_EXEC.md#재현)에 `-e directLinuxFork true`를 추가한다.
기존 `directLinuxThreads true`도 함께 필요하다. host는 `ROOT/tmp/thread-fifo`와
`ROOT/input`을 준비하고 loader로 `/bin/fork-static` 또는 `fork-dynamic`을 실행한다.
시나리오는 `fork-leader`, `fork-worker`, `fork-exited-leader`, `private-clone`,
`unshare-wait`다.

Android MCP로 실제 앱 UID에서 설치·실행·정책·결과를 확인했다. 위 시간 경과 후
proc 조회 오류는 재실행으로 보완했다. 진단 APK 설치도 공간 부족으로 두 번
실패했으며, 이전 실험의 생성 APK/재생성 가능한 fixture 복사본만 삭제하여 약
903 MiB 여유를 만든 후 성공했다. 설치 실패 직후 구 APK로 실행한 진단은 새 fixture의
근거에서 제외했다. Linux clone/kernel의 1차 자료를 확인했고,
UI/외부 앱 작업이 없어 Replicant/codex_apps는 사용하지 않았다. 사용 가능한 기기는
4 KiB/API 35/SELinux Disabled redroid이며 **stock_policy_verified=false**다.
