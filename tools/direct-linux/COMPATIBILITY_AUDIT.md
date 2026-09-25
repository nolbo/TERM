# Direct Linux 호환성 감사 — 2026-09-25

현재 Direct Linux는 짧은 명령의 성능 개선과 일부 실제 프로그램 실행을 검증한
실험 런타임이다. **일반 Debian 환경 또는 Rootless와의 호환성 동등성은 아직
확립되지 않았다.** 아래는 현 구현에서 찾은 45개 개선·검증 항목이다. 요청 범위는
진단이며, 이번 감사에서 런타임을 수정하거나 작업 중인 스레드 구현을 완료하지 않았다.

이후 수정과 현재 완료 증거는 [구현 진행 상태](COMPATIBILITY_PROGRESS.md)를 따른다.
아래 표와 syscall inventory는 감사 당시 커밋의 기록으로 보존한다.

## 범위와 증거

- 기준 소스: `3f4818bc57aa71d0f132c7ac5be1c284d1015edf`의 `tools/direct-linux/`.
  소스 링크는 탐색용이며, 정확한 판단은 이 커밋의 동일 파일·함수를 기준으로 한다.
  작업 트리의 `threads.c`, `threads.S`, `threads.h` 및 관련 변경은 **미커밋 WIP**로
  별도 검토했다. 지원 완료로 집계하지 않았다.
- `guest.c`의 전체 dispatcher/BPF와 ELF, 메모리, 파일, FD, 장치, 네트워크,
  신호, bootstrap 코드 및 관련 테스트·결과를 대조했다. 제품의 runtime enum과
  backend 연결도 확인했다. 설계 기준은 [Direct Linux 설계](../../docs/DIRECT_LINUX_EXPERIMENT.md).
- 저장된 [최신 전체 실행 결과](results/startup-runtime-2026-09-25.json)는
  loader SHA-256 `6a6a68e44bf174f2fa6c4f111770ce23d6e1be8a2728f142f299ad1f8115a8a3`,
  118회 top-level 실행, 두 엔진의 PTY·네트워크·신호 시나리오를 기록한다.
  이 숫자는 **의도한 거부 및 알려진 비교 엔진 실패도 포함**하며 118개 지원 기능을 뜻하지 않는다.
  fixture는 Ubuntu ARM64 glibc 2.39 등의 복사본이고 완전한 Debian 설치가 아니다.
- Android MCP로 현재 기기의 API 35, SELinux `Disabled`, 설치된 loader와 저장된
  결과를 확인했다. 현재 기기에 마지막으로 남은 보고서는 94회 실행/54개 timing의
  다른 부분 실행이다. 저장소의 118회 전체 보고서와 혼동하지 않는다.
  기존 실행의 initial seccomp는 0, `stock_policy_verified=false`다.
  **이번 감사에서 Android instrumentation을 다시 실행하지 않았다.**
- 이번에 커밋 소스를 별도로 컴파일하여 실제 Linux 커널에서 두 결함을 새로 재현했다.
  아래 재현 절과 [원시 결과](results/compatibility-host-2026-09-25.json)를 참조한다.

분류: **재현**은 실제 동작 차이, **코드**는 명시적 거부·미구현·구조적 제한,
**검증**은 실패를 단정할 수 없는 검증 공백이다. 한 항목에 여러 분류가 있으면
본문에서 구분한다. P0는 데이터/상태 정확성 또는 일반 Linux 환경의 선행 조건,
P1은 일반 도구 호환성, P2는 수요·플랫폼 지원에 따라 범위를 정할 확장이다.
플랫폼이 금지하는 권한을 획득하거나 다른 runtime으로 몰래 전환하는 것은 개선안이 아니다.

## 프로세스·실행 파일·메모리

| ID / 우선순위 | 발견과 영향 | 개선 및 완료 증거 |
| --- | --- | --- |
| C01 / P0 | **검증 — stock Android 정책.** redroid의 SELinux Disabled/initial Seccomp 0 결과만 있다. 파일의 실행 매핑, self `process_vm_readv/writev`, SIGSYS 필터가 일반 앱 도메인에서 허용되는지 확정되지 않았다. | enforcing ARM64 기기에서 동일 APK·target SDK·일반 UID로 설치/실행/필터 중첩/오류를 검증. 금지 기능은 명시적인 지원 불가로 보고. `loader.c:check_policy`, `guest.c:memory_copy/dl_install`. |
| C02 / P0 | **코드·검증 — 페이지/커널/Bionic 차이.** 측정 기기는 4 KiB. ELF는 host page와의 offset/vaddr 합동 및 LOAD 페이지 비중첩을 요구한다. 16 KiB 정렬 loader가 일반 4 KiB 정렬 guest ELF의 실행을 보장하지 않는다. bootstrap은 Bionic TLS의 커널 rseq 참조 등을 남기고 나머지 초기 매핑을 DONTFORK 처리한다. | 4/16 KiB 기기, 지원 Android 최소/최신 버전 및 커널에서 실제 배포판 ELF와 fork/exec 반복 검증. 오래된 커널의 syscall fallback도 확인. `exec.c:read_image`, `bootstrap.h:bootstrap_fork_policy`. |
| C03 / P0 | **코드 — pthread 미지원.** 커밋된 `guest_clone`은 fork와 제한된 vfork만 허용한다. 공유 VM/TLS/스레드를 사용하는 프로그램은 실행 기반이 없다. `clone3`, `rseq`, `get_robust_list`도 ENOSYS. | TLS, join/detach, clear-child-TID, robust/PI mutex, cancellation, 신호, thread-local destructor, CPU migration 및 종료 회수의 실제 glibc 테스트. WIP 파일 존재를 완료로 간주하지 않음. |
| C04 / P0 | **코드 — spawn/vfork 의미 제한.** `clone3` 미지원, 별도 스택의 shared-VM clone 거부. vfork는 COW fork+pipe로 부모를 대기시키므로 공유 메모리 의미가 다르고 중첩 vfork도 거부한다. | 실제 배포판 libc의 `posix_spawn[p]`, Python subprocess 옵션, shell, 파일 동작/시그널 속성을 Rootless와 비교. 단순 fork 경로로만 테스트를 우회하지 않음. `guest.c:guest_clone`. |
| C05 / P0 | **코드·검증 — WIP 스레드 수명/병행 exec.** WIP는 128개 thread 한도, 여러 active thread가 있는 fork/exec와 nonleader exec를 거부한다. FD/FS 공유 조합, 전역 매핑·신호 상태, 락 재진입, exit 후 stack 회수가 아직 제품 검증되지 않았다. | worker가 살아 있는 동안 fork/spawn/exec, leader 선종료, fd-table 분리, thread 생성 실패·TID 재사용·장시간 스트레스. 기존 WIP의 제한을 명시하고 별도 단계로 완성. `threads.c:dl_fork_begin/dl_exec_begin`, `fds.c`. |
| C06 / P1 | **코드 — FD 기반 실행.** `execveat`는 EOPNOTSUPP. `/proc/self/fd`도 완전하지 않아 `fexecve`의 두 구현 경로를 모두 기대할 수 없다. | AT_EMPTY_PATH, 상대 dirfd, unlink 후 실행, script+CLOEXEC, 올바른 errno/원래 이미지 유지 검증. `guest.c:handle`. |
| C07 / P1 | **코드 — 인자/환경 제한.** argv 128개, env 256개, 문자열당 128 KiB, 고정 약 2 MiB 문자열 예산이다. 빌드 명령·glob·큰 환경이 짧은 문자열만으로도 E2BIG가 될 수 있다. `RLIMIT_STACK` 연동도 없다. | 실제 커널의 바이트/개수 한도에 맞춘 유연한 보관 및 경계 테스트. `exec.c:ARG_LIMIT/ENV_LIMIT/vector/stack_string`; 단순히 96개 인자 벤치 통과로 충분하지 않음. |
| C08 / P1 | **코드·검증 — ELF 허용 범위.** ARM64 ELF64 LE ET_EXEC/ET_DYN만 지원. 파일/이미지 span/segment 128 MiB, PH 128개, 48-bit 상한과 추가 entry/PHDR/LOAD 제약이 있다. W+X LOAD와 executable stack도 거부한다. | 큰 debug ELF, static PIE, 다양한 linker/DSO/TLS 및 유효하지만 비표준인 배치를 검사하고 불필요한 한도를 제거. 다른 ISA는 별도 범위로 명시. W^X/Android 정책은 유지하며 BTI/PAC 등 GNU property 처리를 검증. `exec.c:read_image/map_image`. |
| C09 / P1 | **코드·검증 — 실행 파일 권한/동시 쓰기.** setuid/setgid bit가 있으면 EACCES. 메타데이터 snapshot 뒤 payload는 file-backed mmap으로 읽어 실행 중 파일 변경·truncate를 막는 ETXTBSY 수명 모델이 없다. | 실제 권한 상승 없이 패키지의 mode bit를 다루는 정책을 정하고, writable FD/실행 중 truncate/교체 경쟁을 검사. 모든 파일 매핑을 무조건 snapshot하는 식으로 MAP_SHARED/EOF 의미를 바꾸지 않음. `exec.c:read_image/map_image`. |
| C10 / P1 | **코드·검증 — in-process exec 상태.** signal disposition/CLOEXEC/robust-list/clear-TID 일부만 명시적으로 reset한다. kernel exec 전체 효과와 같다고 볼 수 없으며 commit 뒤 실패는 exit 126이다. | 실패 전 이미지 보존, commit 뒤 실패의 종료 상태, auxv/CPU state/프로세스 이름 및 향후 timer/rseq/altstack 지원과 연결된 reset 표를 만들고 검증. WIP thread exec는 C05. `exec.c:reset_exec_state/dl_exec`, `guest.c:SYS_execve`. |
| C11 / P0 | **재현 — DONTFORK 뒤 매핑 소유권 불일치.** `madvise`는 kernel로 직행하지만 `regions`는 fork에 복사된다. 자식에서 사라진 guest 영역을 새 runtime 매핑이 차지하면 `dl_unmap_all`이 새 메모리까지 해제한다. | 상속 속성/실제 생존 구간을 반영하여 자식의 소유권을 재구성. 새 exec 준비 영역 보존 및 DONTFORK/DOFORK/WIPEONFORK 경계 회귀 검사. 이번 재현은 bookkeeping 결함이며 전체 guest exec crash를 직접 재현한 것은 아님. |
| C12 / P1 | **코드 — 메모리 API 범위.** `mremap`, `msync`, `memfd_create`, `mlock*`, `membarrier` 등이 ENOSYS. MAP_FIXED는 기존 guest-owned 구간만 덮을 수 있어 비어 있는 고정 주소 매핑도 거부한다. | 실제 allocator/JIT/database 요구에 맞춰 mapping 수명·move/resize/flush를 구현. host runtime 영역 보존, shared/file mapping과 exec reset을 함께 검증. `memory.c:dl_mmap`, syscall 목록. |
| C13 / P1 | **코드·검증 — 자원 한도.** brk 64 MiB, 주 스택 8 MiB, 매핑 구간 2048, runtime signal stack 256 KiB, 중첩 wait 64개가 고정이다. `prlimit64`로 보고/변경하는 값과 guest 가상 자원의 한도가 일치하지 않는다. | 큰 heap·많은 mmap·재귀 handler·대형 빌드로 경계/오류/회복을 검증. 동적 bookkeeping 또는 명시적 한도 노출, guard/overflow 처리. `runtime.h`, `memory.c`, `exec.c`, `signals.c`. |

## 신호·파일·기본 Linux API

| ID / 우선순위 | 발견과 영향 | 개선 및 완료 증거 |
| --- | --- | --- |
| C14 / P0 | **코드·검증 — 번역 중 blocking의 신호 처리.** dispatcher는 모든 blockable signal을 막는다. network/wait와 달리 FIFO `openat`, `fcntl(F_SETLKW)`, vfork parent pipe wait 등은 원래 mask를 복구하지 않고 block할 수 있다. 신호로 중단하려는 작업이 계속 대기할 구조다. | 준비된 FIFO/잠금 경합에서 SIGINT/SIGTERM/EINTR/SA_RESTART/nonlocal jump를 실측. guest mask로 전환하되 임시 FD·락·메모리의 회수를 설계. 현재는 코드 확인이며 Android hang 재현은 미실시. `guest.c:dl_install/guest_clone`, `files.c:walk`, `network.c:interruptible` 비교. |
| C15 / P1 | **코드 — guest altstack.** `sigaltstack`는 EOPNOTSUPP이며 private runtime altstack과 guest SA_ONSTACK 수명이 구분되지 않는다. | guest alternate stack, SS_DISABLE/SS_AUTODISARM, stack overflow handler, signal 중 exec/longjmp 및 스레드별 stack 검증. `guest.c:handle`, `signals.c`. |
| C16 / P1 | **코드 — SIGSYS 예약.** action 등록 거부, 물리 mask에서 항상 제외, 외부 SIGSYS는 exit 159. 일반 Linux 프로그램의 SIGSYS 처리·대기 의미와 다르다. | transport와 guest 신호의 논리적 구분 및 kill/queue/wait/siginfo 검증. 필터를 비활성화하는 해결책은 아님. `guest.c:dispatch`, `signals.c:dl_signal_action/dl_signal_wait`. |
| C17 / P1 | **검증·코드 — 전체 signal context와 restart.** mask와 중첩 longjmp는 기존 통과. 하지만 번역된 host syscall에서 받은 PC/SP/register context 전체의 guest 변환, 수정 후 복귀, SVE/SME/extra context, cancellation 및 모든 SA_RESTART 조합은 미검증. exec는 첫 FPSIMD record만 초기화한다. `restart_syscall`은 ENOSYS. | ucontext register 편집/setcontext, extended CPU state, stop/continue 뒤 재시작, 모든 blocking 경로의 handler 재진입 비교. SA_RESETHAND를 미지원으로 분류하지 않음: 기존 실제 검사 통과. |
| C18 / P1 | **코드 — timer/queued signal API.** `getitimer/setitimer`, `timer_*`, `timerfd_*`, `signalfd4`, `rt_sigqueueinfo/rt_tgsigqueueinfo` 미지원. timeout/watchdog/event loop에 영향 가능. | timer 만료/overrun, mask·queue·FD 이벤트, fork/exec reset, pthread signal 선택을 함께 검증. 임의 no-op 성공을 반환하지 않음. |
| C19 / P0 | **코드 — 가상 identity/소유권.** uid/gid/stat/chown은 실제 Android 자격 증명이며 set/get-res-id·groups·capget/capset 일부는 ENOSYS. Rootless의 fake root와 다르다. | 실제 OS 권한을 얻지 않고 guest identity와 파일 메타데이터의 일관성/영속성 구현. apt/dpkg maintainer script, 소유권·mode 보존, 재시작/복구 검사. auxv와 peer credentials까지 같은 모델 필요. |
| C20 / P0 | **코드 — hardlink/FIFO/node 생성.** `linkat`, `mknodat` 미지원. hardlink 있는 archive·package backup과 `mkfifo`가 정상 의미를 갖지 못한다. | host 정책에 맞는 hardlink/가상 inode·nlink, rename/unlink 및 restart 내구성. FIFO는 실제 blocking 의미까지 구현(C14). 임의 host device 노출 없이 device node 정책을 구분. |
| C21 / P1 | **코드 — 최신 pathname/임시 파일 API.** O_TMPFILE 명시적 거부, `openat2/faccessat2/fchmodat2` ENOSYS. 기존 변환은 모든 새 flag/resolve 정책을 포괄하지 않는다. | dirfd, empty path, no-follow, 실제/유효 ID, `RESOLVE_*`, 임시 파일 연결, fallback errno 비교. 특히 openat2 이름만 지원하고 보안 flag를 무시하지 않음. `files.c:dl_filesystem`. |
| C22 / P1 | **코드·검증 — ACL/xattr/시간·경로 경쟁.** 기본 xattr·chmod·symlink·rename는 통과했지만 ACL/security namespace/file capabilities·default ACL·전체 경쟁은 미검증. symlink timestamp는 Linux 5.8+ 경로에 의존. | tar/cp/rsync 및 package metadata roundtrip, 구형 커널, removed cwd/root rename/symlink 교체의 결과와 errno 비교. Android의 DAC/SELinux를 가상 성공으로 우회하지 않음. `files.c:walk/attributes/dl_filesystem`. |
| C23 / P1 | **코드 — proc 보기.** `/proc/self/exe`의 정확한 문자열 readlink만 특별 처리. `/proc/self/fd`, task, maps, status, cmdline, auxv와 다른 PID 보기는 일반 Linux 환경처럼 구성되지 않는다. | root와 실행 이미지 기준의 최소 proc 표현, FD 재열기/읽기/링크 일관성, exe rename/unlink, process introspection 검증. host proc 전체 bind로 해결하지 않음. `files.c:SYS_readlinkat`. |
| C24 / P1 | **코드·검증 — devpts/중첩 PTY.** 고정 `/dev/null/zero/random/urandom/tty`만 제공하며 해당 dev 디렉터리 변경은 제한한다. `/dev/ptmx`, devpts, `/dev/fd`, stdin/out/err, `/dev/full` 등 일반 뷰가 없다. | guest 안에서 openpty/forkpty/ttyname, nested terminal/tmux/SSH PTY 실행, 올바른 고정 장치 stat/open/변경 errno. 기존 host 제공 PTY 성공과 구분. `devices.c`. |
| C25 / P1 | **재현 — FD 고갈 상태의 close_range.** 정리를 위해 먼저 `/proc/self/fd`를 열므로 EMFILE에서 -24, 동일 kernel close_range는 0. 장애 복구나 process launch cleanup이 실패한다. | 추가 FD가 없어도 닫기/mark-CLOEXEC가 가능한 경로와 EMFILE/ENFILE/중간 오류 회복 검증. C05의 공유 FD-table 분리와 함께 설계. `fds.c:sweep`. |
| C26 / P1 | **코드·검증 — private FD와 한도.** root/vfork 내부 FD가 실제 번호 공간을 사용하며 256 이상으로 복제한다. low RLIMIT_NOFILE, 해당 번호 대상 close/dup/fcntl, SCM_RIGHTS 등 모든 경로가 일반 FD 공간과 동일하지 않다. | 낮은 한도로 시작/한도 축소/FD 재사용 및 전달을 검사하고 내부 FD 보호와 guest-visible 번호 정책을 일관되게 설계. 루트 FD를 보안 격리의 증거로 사용하지 않음. `loader.c`, `guest.c`, `fds.c`. |
| C27 / P1 | **코드 — 파일 locking/고급 I/O.** `flock`, `fallocate`, `preadv/pwritev[2]`, `splice/tee/vmsplice`, `sync/syncfs/sync_file_range`, `fadvise64/readahead` 등이 ENOSYS. 일부 libc/tool fallback은 가능하지만 전부 검증되지 않았다. | DB/lockfile/sparse file/복사 도구에서 atomicity, flush, offset/flags, short I/O/error 검증. 기본 fcntl/readv/fsync/sendfile/copy_file_range는 이미 있으므로 전부 미지원으로 묶지 않음. |
| C28 / P1 | **코드 — 파일 변경 감시.** `inotify_init1/add_watch/rm_watch` 미지원. `fanotify_*`도 없지만 권한 요구가 다르다. | 일반 개발 watcher부터 경로 변환, rename/unlink/overflow, read/poll 검증. fanotify는 Android 정책과 실제 수요에 따라 지원 범위를 별도로 결정. |
| C29 / P1 | **코드·검증 — 일반 process 정보/제어.** `sched_*`, `get/setpriority`, `getcpu`, `times`, `sysinfo`, `gettimeofday`, `get/setrlimit`, guest `prctl` 등이 ENOSYS. clock/gettimeofday의 vDSO나 prlimit fallback 때문에 일부 앱은 우연히 통과할 수 있다. `uname`은 host 값 그대로다. | sysconf/CPU affinity/thread naming/resource limits/time의 libc 경로와 raw syscall을 둘 다 검사. 실제 가능한 CPU/메모리/커널 정보를 일관되게 제공하고 금지 제어에는 정확한 errno. |
| C30 / P2 | **코드·검증 — 추가 IPC/비동기/메모리 ABI.** SysV IPC, POSIX mqueue, native AIO/io_uring, pidfd 계열, NUMA/pkey, futex_waitv/새 futex, userfaultfd 등이 목록상 미지원. 모두 같은 난이도·권한은 아니다. | 실제 앱 요구를 수집하여 기본 IPC부터 우선순위 결정. Android/kernel 지원 여부, fd/path/identity/exec 수명 영향과 fallback을 개별 검증. 단순 syscall 직행을 완료로 보지 않음. |

## 네트워크·배포판·제품 통합

| ID / 우선순위 | 발견과 영향 | 개선 및 완료 증거 |
| --- | --- | --- |
| C31 / P1 | **재현 — 긴 Unix pathname.** `/proc/self/fd/<rootfd>` 접두어가 sun_path 공간을 소비한다. 저장된 현재 결과에서 100바이트 이름은 Direct ENAMETOOLONG, Rootless 성공. | 전체 guest pathname 길이를 유지하는 endpoint 표현과 독립 프로세스 간 동일성/정리 검증. 이름을 자르는 해결책은 아님. `network.c:address`, `UNIX_SOCKETS.md`. |
| C32 / P1 | **코드 — abstract/autobind/batch Unix socket.** abstract와 길이가 family뿐인 주소 입력 거부. named Unix recvmmsg는 제한하며 sendmmsg는 ENOSYS. | abstract namespace 및 credential 경계, autobind, 배치 주소 변환/부분 성공/타임아웃 검증. anonymous socketpair와 pathname stream/datagram의 기존 성공은 보존. `network.c:address/dl_network`. |
| C33 / P1 | **코드·검증 — Unix 주소 수명·credentials.** 주소 변환 후 kernel 호출까지 경로 snapshot이며 동시 rename/rebind의 전체 의미는 미검증. foreign pathname 출력은 EXDEV. 일반 SCM_RIGHTS는 통과했지만 peer credential 가상화 및 모든 ancillary error/FD 정리는 별도 문제다. | 독립 launcher·동시 namespace 변경, SO_PEERCRED/SCM_CREDENTIALS, 주소 출력 EFAULT와 FD 전달 실패, 종료 뒤 자원 회수를 비교. `network.c:output_address/interruptible`. |
| C34 / P2 | **코드 — socket family.** AF_INET/INET6/UNIX 외 socket은 EAFNOSUPPORT. netlink 기반 interface 조회와 packet/raw 관련 도구가 제한될 수 있다. | 허용 가능한 조회/통신부터 추가하고 Android의 netlink·raw 권한을 구분. 기존 IPv6/UDP/SCM_RIGHTS 지원을 미지원으로 분류하지 않음. `network.c:dl_network`. |
| C35 / P1 | **검증 — 실제 DNS/NSS 통합.** 로컬 glibc UDP/TCP resolver와 hosts-file 테스트만 있다. fixture의 NSS는 `hosts: files`, resolver port는 테스트 서버를 직접 지정한다. | 배포판 getaddrinfo의 DNS/NSS 경로, Android 네트워크별 DNS 갱신, IPv6·search·실패/재시도·VPN/split DNS를 검증. 로컬 DNS 전송 통과만으로 시스템 통합 완료를 주장하지 않음. |
| C36 / P1 | **검증 — 외부 통신/인증서.** loopback HTTP/TLS, 테스트 CA/hostname 오류 검사는 통과. 외부 HTTPS/Git/SSH/package mirror, 실제 CA store/proxy/network 변경은 미검증. | 독립 rootfs CA와 시간/프록시 설정으로 실제 인증·오류·재연결 검증. 인증서 검증 비활성화로 성공시키지 않음. `NETWORK.md`, 결과의 `external_dns_verified=false`. |
| C37 / P0 | **코드·검증 — 완전한 Debian/apt/dpkg.** 복사한 Ubuntu fixture 외에 Direct 전용 설치/검증/복구 및 package DB lifecycle이 연결되지 않았다. apt/dpkg install/upgrade/remove/configure/recovery의 증거가 없다. | 새 독립 Debian rootfs에서 maintainer scripts, 권한·링크·lock·conffile, 중단 후 dpkg 복구와 재시작을 검증. 기존 Rootless rootfs/DB를 공유하지 않음. C03/C04/C19/C20 등이 선행. |
| C38 / P1 | **코드·검증 — 저장소 의미/노출.** 실험은 app-private fixture. workspace 공유, 외부 저장소/SAF의 rename/link/permission/case/fsync 차이 및 공간 부족 복구 계약이 없다. | 전용 root·package DB와 명시적 workspace를 분리하고 storage별 지원 범위·errno·내구성 검증. 앱 전체 private 경로를 guest에 노출하지 않음. |
| C39 / P1 | **코드·검증 — 사용자 환경.** launcher는 PATH=/usr/bin:/bin, HOME=/, LANG=C, TMPDIR=/tmp 및 isatty일 때 TERM만 설정한다. 배포판 사용자·locale·timezone·terminfo·HOME/dotfile 초기화가 아니다. | 한글/UTF-8 파일·터미널·locale, passwd/group/NSS, writable home/tmp, timezone·TERM/terminal capability 및 안전한 환경 전달 검증. `loader.c:main`. |
| C40 / P1 | **검증 — 실제 대화형 앱.** dash/stty, job control, resize, Ctrl-C/Ctrl-Z, HUP는 저장된 테스트 통과. bash/zsh/readline, vim/nano/less, tmux/screen, 대화형 SSH·중첩 PTY는 입증되지 않았다. | 장시간 입력·resize·foreground group·detach/reattach·종료 시나리오로 검증. 기존 PTY 보고서의 `ui_lifecycle_verified=false`를 해소. |
| C41 / P1 | **검증 — 실제 개발 workload.** 단일 fixture의 Python imports/로컬 Git만으로 Python threads/multiprocessing/venv/pip, Git network/hooks/submodule, make/cmake/ninja/compiler, Node/JVM/Go/Rust/JIT를 보장할 수 없다. | 동일 Debian 패키지·입력으로 기능부터 검사하고 thread/process/FD/memory 장시간·다중 세션 스트레스. 실패 syscall·기능을 매핑하고 실제 필요에 따라 C30 범위를 결정. |
| C42 / P0 | **코드 — backend/session 통합.** 제품 SessionRuntime/Backend에 Direct가 없고 standalone opt-in test APK뿐이다. 선택·생성·attach/detach·resize·terminate·서비스 수명 계약이 연결되지 않았다. | 공통 backend 뒤에서 구현하고 Activity 재생성/작업 제거/재연결과 실제 owner 사망을 검증. 실패 시 Rootless로 암묵 전환하지 않음. 제품 권한 없는 Native+Rootless 경로 유지. |
| C43 / P0 | **코드·검증 — Android 경계와 격리 계약.** 동일 UID/in-process trampoline은 악성 guest sandbox가 아니다. 경로 walker/내부 FD 보호만으로 별도 host 권한 격리를 주장할 수 없다. Direct 전용 broker/storage identity도 미통합. | 기존 typed capability broker의 인증·검증·최소 노출·deny-by-default 및 환경별 grant 적용. 가능한 위협 모델을 명확히 하고 권한 없는 통합과의 양립 검증. |
| C44 / P2 | **코드·플랫폼 — 완전한 kernel 기능.** mount/namespace/cgroup/systemd, ptrace/guest seccomp, BPF/perf/module/key/security APIs 등은 미구현 또는 Android 정책·권한에 의존한다. Rootless도 모든 기능을 제공하지 않는다. | syscall 목록의 각 항목을 구현 가능/제한된 에뮬레이션/플랫폼 불가로 분류. user namespace·root·정책 완화를 필수로 만들지 말고 실제 kernel 기능은 명시적 AVF 선택으로 구분. guest seccomp/ptrace는 runtime의 host trampoline까지 포함하는 의미 설계가 선행. |
| C45 / P1 | **검증 — 호환성 회귀/비교 기준.** 테스트가 좁은 fixture와 opt-in 플래그에 흩어져 있고 전체 배포판·기기 matrix가 없다. Rootless 자체의 실패도 존재한다. | 아래 syscall inventory와 기능별 필수 검사로 지원표를 만들고 같은 package/input/정책에서 비교. negative expectation을 positive 지원으로 세지 않음. reference 실패는 따로 재현·표시하고 두 엔진의 공통 기능 정확성을 검사. |

## 전체 ARM64 syscall 대조

[318개 전체 목록](results/compatibility-syscalls-2026-09-25.tsv)은 NDK
`28.0.13004108`, repository wrapper의 `aarch64-linux-android31`,
`sys/syscall.h`를 전처리한 **이 NDK의 SYS_* 이름 범위**다. 미래 syscall,
모든 ioctl/flag/구조체 조합 또는 해당 기기 커널의 실제 구현 목록을 의미하지 않는다.

| 분류 | 개수 | 의미 |
| --- | ---: | --- |
| direct | 46 | BPF에서 kernel로 직행. Android 정책/의미 차이 검증은 별도. exit profiling 시 trap되는 예외가 있음. |
| conditional-direct | 8 | 특정 주소/mask가 NULL일 때만 직행, 나머지는 handler. |
| forwarded | 17 | dispatcher를 거쳐 원래 kernel syscall로 전달. |
| handled | 50 | dispatcher에 처리 분기가 있음. 부분 지원·제약 포함. |
| explicit-EOPNOTSUPP | 2 | execveat, sigaltstack 명시적 거부. |
| ENOSYS | 195 | 분기가 없어서 default ENOSYS. |

**123개에 분기가 있다는 수치도 호환률이 아니다.** fork/open/socket처럼 핵심
syscall 하나의 flag/상태 제약이 전체 앱을 막을 수 있다. 반대로 ENOSYS인 syscall도
glibc vDSO/다른 syscall fallback으로 일부 사용 경로는 동작할 수 있다. 195개 전부를
무조건 구현해야 한다는 뜻도 아니다. 일반 도구의 차단 요인(C18–C30)과 실제 kernel
권한이 필요한 기능(C44)을 구분해서 우선순위를 정해야 한다.

분류기는 기준 소스의 구조에 맞춘 정적 감사 도구이며 C parser가 아니다. 향후
dispatcher 구조·명시적 거부 항목이 바뀌면 분류기도 검토해야 한다.

재생성(기존 NDK/Clang만 사용):

```sh
audit_dir=$(mktemp -d /tmp/direct-linux-audit.XXXXXX)
audit_revision=3f4818bc57aa71d0f132c7ac5be1c284d1015edf
git show "$audit_revision:tools/direct-linux/guest.c" > "$audit_dir/guest.c"
TERMINAL_ANDROID_NDK="$PWD/.android-sdk/ndk/28.0.13004108" \
  bash tools/iphone/android-clang.sh -dM -E -include sys/syscall.h -x c /dev/null \
  > "$audit_dir/syscalls.macros"
python3 tools/direct-linux/compatibility-inventory.py \
  "$audit_dir/guest.c" "$audit_dir/syscalls.macros" "$audit_dir/syscalls.tsv"
cmp "$audit_dir/syscalls.tsv" tools/direct-linux/results/compatibility-syscalls-2026-09-25.tsv
```

## 이번 감사의 새 재현

아래 검사는 Ubuntu ARM64 host의 실제 커널을 사용한 **작은 구성요소 재현**이며,
Android 정책이나 guest 전체 실행을 증명하지 않는다. 기존 WIP의 `memory.c` 등을
섞지 않고 위 커밋의 소스를 사용한다. [재현 소스](results/compatibility-host-2026-09-25.c)는
향후에도 실패를 고정하는 CI 테스트가 아니라, 감사 시점의 동작 차이를 기록하는 진단용이다.

```sh
for audit_file in runtime.h memory.c fds.c; do
  git show "$audit_revision:tools/direct-linux/$audit_file" > "$audit_dir/$audit_file"
done
cc -O2 -Wall -Wextra -Werror -I "$audit_dir" \
  tools/direct-linux/results/compatibility-host-2026-09-25.c \
  "$audit_dir/memory.c" "$audit_dir/fds.c" -o "$audit_dir/compatibility-host"
"$audit_dir/compatibility-host"
```

1. **C11:** 추적 중인 3-page 영역의 중앙 페이지만 DONTFORK → fork → 사라진
   구간에 추적되지 않는 새 매핑 확보 → `dl_unmap_all`이 새 영역까지 제거한다.
   결과는 `fresh_mapping_survived=false`. exec의 준비 영역/새 스택이 먼저 raw mmap되는
   구현과 결합하면 문제가 된다. 재현은 비어 있는 동일 주소에 명시적으로 매핑하므로,
   자연스러운 주소 재사용 확률이나 Android guest의 exec crash를 입증하는 것은 아니다.
2. **C25:** 자식의 soft RLIMIT_NOFILE을 64로 낮추고 `/dev/null` FD로 채운다.
   Direct `dl_close_range(3, UINT_MAX, 0)`는 `-EMFILE`, 동일 상태에서 뒤이어 호출한
   kernel `close_range`는 성공 0. 원래 부모 프로세스/사용자의 한도는 변경하지 않는다.

## 검증 결과와 진행 순서

이번 실행: 정적 inventory 318개 생성·재생성 일치, 기준 소스와 두 host 재현의
`-Wall -Wextra -Werror` 컴파일/실행 성공. 결과는 **결함 두 건 재현 성공**이며
해당 기능의 호환성 테스트 통과가 아니다. 문서 링크·diff·artifact 정합성도 검사했다.
Android instrumentation/Gradle canonical build는 재실행하지 않았다. 런타임/Android
소스 변경 없이 문서·감사 도구·증거만 추가했기 때문이다. stock enforcing/16 KiB
기기는 현재 증거에 없어 C01/C02를 열린 상태로 남긴다.

Android MCP는 현재 기기 정책과 저장 결과의 provenance 확인에 사용했다.
Context7은 새 외부 API 도입이 없어 불필요했고, Replicant는 UI 변경/조작,
codex_apps는 외부 서비스 작업이 없어 사용하지 않았다. 이번 Android MCP 실패는 없었다.

권장 순서: **C11/C25 상태·복구 결함과 C14 신호 중단 → C03–C05 thread/spawn
및 C19/C20 파일 의미 → C37 실제 Debian package cycle → 나머지 일반 도구·제품
통합**. C01/C02 기기 정책/페이지 검증은 병행할 선행 gate다. 45개 목록과 전체
syscall 대조는 현재 소스의 알려진 공백을 정리한 것이며, 모든 Linux 프로그램의
미지의 결함까지 없음을 보장하지 않는다.
