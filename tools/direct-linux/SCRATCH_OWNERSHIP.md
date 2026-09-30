# Direct Linux: exec 준비 메모리의 소유권

2026-09-30. C04 shared-VM spawn의 선행 작업으로, exec 준비용 메모리를
`dl_thread`별로 추적한다. **guest shared-VM clone/spawn은 아직 지원하지 않는다.**
[실행 결과](results/scratch-runtime-2026-09-30.json)는 구성요소 검사와 실제
guest 회귀를 구분해 기록한다.

## 문제와 구현

기존 exec는 준비 객체, 새 스택, kernel 재진입용 handoff를 raw mmap으로 만들었다.
실패하면 각각 해제하지만 kernel exec 성공 시에는 기존 VM 소멸에 의존했다.
CLONE_VM의 다른 프로세스가 이전 VM에 남는 경우 이 방식으로는 회수할 수 없다.

`scratch.c`는 각 매핑 앞의 독립 header page에 소유자와 양방향 목록을 보관한다.
고정 개수 배열을 두지 않으며, mmap/mprotect 성공 후에만 목록에 게시한다.
길이 overflow와 할당 실패는 기존 소유 상태를 변경하지 않는다.

- `exec.c`의 준비 객체·스택·handoff와 재진입 경로 모두 현재 task의 목록을 쓴다.
  최초 실행과 kernel 재진입에서는 task 초기화 전부터 첫 record를 소유자로 쓴다.
- 실패 경로는 해당 매핑을 목록에서 제거하고 해제한다. payload가 PROT_NONE이어도
  header는 접근할 수 있으므로 스택 양쪽 guard를 유지하면서 회수할 수 있다.
- 이미지 설치 성공 시 스택 payload를 guest mapping registry로 이전하고 header를
  해제한다. 이후 task 준비 메모리 회수가 실행 중인 guest 스택을 제거하지 않는다.
- 기존 task record 폐기 및 COW fork 자식의 다른 record 정리에서 잔여 목록을
  회수한다. 내부 FD는 기존 FD-table별 수명 관리에 남긴다.

목록 변경은 신호가 차단된 소유 task에서 실행한다. 다른 task의 회수는 그 소유자가
해당 VM을 더 이상 사용하지 않는다는 증거가 있어야 한다. guest clear-child-TID를
덮어쓰거나, exec를 시도했다는 이유만으로 살아 있는 runtime stack을 해제하지 않는다.
header당 한 page와 성공한 스택 이전의 munmap 비용이 추가되며 이번 성능 측정은 없다.

## 검증

`scratch-host-test.c`는 실제 `scratch.c`와 kernel clone/exec를 사용한다.
dispatcher/task registry를 우회하는 구성요소 검사이므로 guest spawn 지원 증거는 아니다.
Ubuntu ARM64 host와 Android 일반 앱 UID에서 각각 다음을 통과했다.

- 512개 동적 매핑, 중간 순서 해제, guest로 이전한 매핑 보존, guard 내부 접근,
  overflow·mprotect 실패 및 RLIMIT_AS로 강제한 할당 실패 후 기존 메모리 보존.
- CLONE_VM 자식 exit, CLONE_VM 자식 exec, CLONE_VM|CLONE_VFORK 자식 exec를 각각
  64회, 합계 192회 실행했다. 실패한 exec 후에도 준비 데이터는 남는다.
- 새 실행 파일이 실제 SIGSTOP을 보낸 것을 waitpid로 확인한 후, 그 프로세스가
  살아 있는 동안 이전 VM의 준비 메모리를 회수했다. 부모 소유 메모리와 이전된
  guest payload는 남고, 회수된 영역은 mincore에서 ENOMEM이다.
- guest가 지정한 clear-child-TID는 runtime용으로 바꾸지 않았으며 kernel의
  exit/exec 정리를 확인했다. 이 값을 회수 시점의 근거로 사용하지 않았다.

별도의 실제 Direct guest host 회귀는 정적·동적 glibc pthread, worker fork,
worker exec의 6개 suite를 통과했다. ELF metadata/인자 snapshot과 signal exec-reset
회귀도 통과했다. Native import/TLS/interpreter/type/relocation 감사도 통과했다.

Android 전체 검사는 **27.593초, 3 tests 통과**, loader 126회 실행, fixture 733개
hash 일치, kernel 재진입 200회다. thread/fork/FD exec/blocking signal/network/PTY
검사를 포함한다. 비교 엔진의 기존 SIGSEGV·FD pin·FIFO restart·Unix 이름 제한과
의도한 거부는 별도로 기록하며 지원 성공으로 세지 않는다.

JDK 17 canonical 빌드와 instrumentation APK 생성·서명 검증이 통과했다.
runtime 단위 검사는 186 pass/1 optional skip, image는 4 pass다.
Android MCP로 설치·실행·결과 및 API 35 ARM64, 4 KiB, SELinux Disabled를 확인했다.
initial Seccomp/CapEff/TracerPid=0인 일반 앱 UID이며 stock enforcing/16 KiB 증거는 아니다.
기존 raw ABI만 사용해 Context7 조회는 필요하지 않았고 UI·외부 앱 작업이 없어
Replicant/codex_apps도 사용하지 않았다. MCP 호출 자체의 실패는 없었다.

실패 및 복구: ELF host 검사는 sandbox의 process_vm_readv 제한으로 처음 실패했고
허용된 host 실행에서 통과했다. Gradle은 cache 쓰기 권한을 적용한 뒤 통과했다.
첫 host pthread 명령의 잘못된 `basic` 인자를 기존 `common`으로 바로잡았다.
첫 Android 전체 검사는 재사용한 TLS 인증서의 9월 27일 만료로 실패했다.
fixture를 새로 만들었으며 인증서 검증을 약화하지 않았다. 첫 실행에서 잘못 지정한
재진입 옵션도 `directLinuxReexec=true`로 고쳐 최종 실행에서 200회를 확인했다.

## 남은 C04 조건

프로세스별 task identity와 exec 선택, 실제 VM 이탈의 자동 감지·회수 연결,
FD snapshot과 kernel clone의 일치, vfork 부모 정지 중 잠금 처리,
SIGKILL 및 준비 상태 변경 도중 사망에 대한 복구가 남았다.
이번 구성요소 검사의 waitpid/새 이미지 SIGSTOP은 제품용 이탈 감지 구현이 아니다.
기존 COW vfork 경로를 진짜 shared-VM vfork로 간주하지 않는다.

## 재현

```sh
clang -O2 -Wall -Wextra -Werror tools/direct-linux/scratch-host-test.c \
  tools/direct-linux/scratch.c tools/direct-linux/syscall.S -o /tmp/direct-linux-scratch-test
/tmp/direct-linux-scratch-test
TERMINAL_ANDROID_NDK="$PWD/.android-sdk/ndk/28.0.13004108" \
  bash tools/iphone/android-clang.sh -O2 -Wall -Wextra -Werror -fPIE -pie \
  -Wl,-z,now,-z,noexecstack,-z,max-page-size=16384 \
  tools/direct-linux/scratch-host-test.c tools/direct-linux/scratch.c \
  tools/direct-linux/syscall.S -o /tmp/direct-linux-scratch-android.so
```

[ELF packaging](ELF.md#reproduction)의 probe 입력에 이 Android 구성요소 binary를
넣는다. fixture는 인증서 유효기간 안에 새로 생성한다. 검사 class는
`dev.terminal.runtime.directlinux.DirectLinuxScratchTest`, 옵션은
`-e directLinuxScratch true -e directLinuxScratchSha256 COMPONENT_SHA256`이다.
이 APK는 opt-in 검사 전용이며 제품 APK에 포함하지 않는다.
