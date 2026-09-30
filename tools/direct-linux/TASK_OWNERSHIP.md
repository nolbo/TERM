# Direct Linux: 공유 VM의 task 관찰과 회수

2026-09-30. C04의 선행 작업으로 process identity와 VM 이탈 관찰을 기존 task
registry에 연결했다. 이 선행 단계 이후 [실제 vfork/spawn](SPAWN_COMPATIBILITY.md)을 연결했으며, 그 경로는 proc 관찰 대신 kernel vfork 완료로 회수한다. 일반 shared-VM clone은 여전히 미지원이다.
[실행 결과](results/tasks-runtime-2026-09-30.json)는 구성요소 검사와 실제 guest
회귀를 구분한다. 전체 C01–C45 목표와 Rootless 대체 가능성은 미완료다.

## 구현과 판단 근거

이전 registry는 모든 record를 현재 TGID의 thread로 간주하고 EXITING 상태의
TID 소멸만 확인했다. 다른 프로세스가 같은 VM을 사용하는 경우에는 실제 kernel
exec 뒤에도 그 프로세스가 살아 있을 수 있으므로 PID 존재만으로 회수할 수 없다.

- `threads.c`는 TID와 TGID를 함께 기록한다. 초기화·thread 생성·COW fork에서
  identity를 갱신하고, 다른 TGID의 살아 있는 record도 exec 경로 선택에 포함한다.
  다른 task가 남아 있으면 현재 VM을 지우는 in-process exec를 선택하지 않는다.
- `tasks.c`는 내부 용도로 `/proc/<tid>/stat`를 최대 2048바이트까지 읽는다.
  숫자 overflow·잘린 입력·예상과 다른 PID를 거부하고, 공백·개행·괄호가 있는
  comm도 처리한다. 실패하면 출력 구조체를 변경하지 않는다. guest procfs 제공과는
  별개이며 내부 FD 생성은 기존 files lock으로 직렬화한다.
- 일반 foreign task는 tgkill의 ESRCH 또는 관찰된 zombie/dead 상태로 회수한다.
  exec 이후에도 살아 있는 task는 초기 VM의 `start_stack`과 **다른 0이 아닌 값**을
  관찰했을 때만 회수한다. guest에는 `PR_SET_MM`을 허용하지 않는다.
- nonleader exec는 kernel이 mm 교체보다 먼저 TID를 교환한다. `exec.c`는 kernel
  exec 직전 전환을 표시하고 실패 시 되돌린다. 표시된 nonleader의 예전 TID가
  사라졌다는 사실이나 group leader의 zombie 상태만으로 회수하지 않고, TGID의
  바뀐 `start_stack`을 요구한다.
- 회수는 기존 `dispose`에 연결하여 runtime stack, exec 준비 목록, signal table
  참조를 정리한다. 다른 FD table의 번호를 현재 table에서 닫지 않는다. guest의
  clear-child-TID를 내부 통지용으로 대체하거나 wait 상태를 소비하지 않는다.

커널 순서는 [Linux v6.17 exec/de_thread](https://github.com/torvalds/linux/blob/v6.17/fs/exec.c),
[exit](https://github.com/torvalds/linux/blob/v6.17/kernel/exit.c),
[proc stat 구현](https://github.com/torvalds/linux/blob/v6.17/fs/proc/array.c)과
[ELF 이미지 설치](https://github.com/torvalds/linux/blob/v6.17/fs/binfmt_elf.c)를 대조했다.
proc stat의 보호 필드가 접근 권한에 따라 0이 될 수 있다는 조건은
[Linux man-pages](https://man7.org/linux/man-pages/man5/proc_pid_stat.5.html)를 따른다.
proc의 state와 다른 필드를 하나의 원자적 스냅샷으로 가정하지 않는다.

관찰 실패는 사망의 증거가 아니다. EMFILE, 접근 거부, 숨겨진 proc, 0인 주소 또는
exec가 같은 주소를 재사용한 경우에는 record를 보존한다. 특히 nonleader가 바뀐
스택 관찰 전에 종료하면 현재 방식으로는 회수하지 못할 수 있다. 이 보수적인
보존은 잘못된 해제를 피하지만 128개 task 한도를 소모할 수 있어 **완성된 spawn
수명 관리가 아니다**. guest 활성화 전에 이 경우도 해결해야 한다.

## 검증

`tasks-host-test.c`는 실제 `threads.c`, `signals.c`, `tasks.c`, `scratch.c`를 사용한다.
등록 부분만 검사 adapter이며 guest dispatcher는 설치하지 않는다. host와 Android
일반 앱 UID에서 각각 8개 시나리오 × 32회, 총 256개의 CLONE_VM 프로세스를 검증했다.

- 정상 exit, SIGKILL, leader exec, worker exec, leader 선종료 후 worker exec.
- 실패한 exec 뒤 데이터 보존, 살아 있는 새 이미지의 이전 VM 자원 회수,
  마지막 signal table 참조 및 runtime stack/준비 매핑의 mincore ENOMEM.
- 부모 메모리, kernel clear-child-TID, guest가 나중에 받을 waitpid 상태 보존.
- nondumpable 자식의 proc 은닉, EMFILE, 같은 stack 주소를 관찰하도록 주입한
  조건에서 성급한 회수 방지. host는 보호 필드 0, Android는 ENOENT를 반환했다.
- 다른 프로세스가 남아 있으면 kernel exec 경로, 회수 뒤에는 단독 exec 경로 선택.

SIGKILL 검사는 소유권 게시가 끝나 대기 중인 자식을 종료한다. 목록 변경·공유 잠금
보유·초기화 도중 SIGKILL 복구를 검증한 것은 아니다. 이 복구, 실제 shared-VM 생성과
FD snapshot의 일치, vfork 부모 정지 중 잠금, process별 상태 분리가 남아 있다.

기존 실제 guest host 검사는 정적·동적 pthread common, worker fork, worker exec의
6개 suite와 ELF metadata/인자 snapshot을 통과했다. native import/TLS/interpreter/
type/relocation 감사와 서명 검사도 통과했다. Android 최종 검사는 **28.525초,
3 tests 통과**, loader 126회 실행, fixture 733개 hash 일치, kernel 재진입 200회다.
기존 비교 엔진의 실패 및 의도한 거부는 지원 성공으로 세지 않는다.

JDK 17 canonical 빌드와 instrumentation APK 생성이 통과했다. runtime 단위 검사는
186 pass/1 optional skip, image는 4 pass다. 성능 비교는 재실행하지 않았다.
Android MCP로 설치·실행·결과와 API 35 ARM64, 4 KiB, SELinux Disabled를 확인했다.
initial Seccomp/CapEff/TracerPid=0인 일반 앱 UID이며 stock enforcing/16 KiB 증거는 아니다.
Context7은 필요한 kernel 순서 정보를 제공하지 못해 위 원문으로 보완했다.
UI·외부 앱 작업이 없어 Replicant/codex_apps는 사용하지 않았고 MCP 호출 오류는 없었다.

실패 및 수정: 첫 Android 구성요소 검사는 nondumpable proc를 항상 읽을 수 있다고
가정하여 실패했다. 허용 가능한 접근 오류도 검사하되 살아 있는 자식과 매핑 보존을
계속 확인하도록 고쳤다. 제품 코드는 원래 모든 관찰 오류에서 회수를 보류했다.
host 검사 코드의 AArch64 미지원 SYS_pause는 futex 대기로 고쳤으며, ELF 검사는
sandbox의 process_vm_readv 제한 밖에서 허용된 실행으로 통과했다.

## 재현

```sh
clang -O2 -Wall -Wextra -Werror tools/direct-linux/tasks-host-test.c \
  tools/direct-linux/scratch.c tools/direct-linux/syscall.S tools/direct-linux/threads.S \
  -o /tmp/direct-linux-tasks-test
/tmp/direct-linux-tasks-test
TERMINAL_ANDROID_NDK="$PWD/.android-sdk/ndk/28.0.13004108" \
  bash tools/iphone/android-clang.sh -O2 -Wall -Wextra -Werror -fPIE -pie \
  -Wl,-z,now,-z,noexecstack,-z,max-page-size=16384 \
  tools/direct-linux/tasks-host-test.c tools/direct-linux/scratch.c \
  tools/direct-linux/syscall.S tools/direct-linux/threads.S -o /tmp/direct-linux-tasks-android.so
```

[ELF packaging](ELF.md#reproduction)의 probe 입력에 Android 구성요소 binary를 넣는다.
검사 class는 `dev.terminal.runtime.directlinux.DirectLinuxTaskOwnershipTest`, 옵션은
`-e directLinuxTaskOwnership true -e directLinuxTaskOwnershipSha256 COMPONENT_SHA256`이다.
이 APK는 opt-in 검사 전용이며 제품 APK에 포함하지 않는다. 전체 guest 회귀에는
LoaderTest와 ExecTransitionTest 및 기존 thread/fork/execfd/network/PTY 옵션을 함께 쓴다.
