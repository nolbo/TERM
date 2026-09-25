# Direct Linux: 공유 VM과 신호 상태의 소유권

C04 spawn/vfork를 위한 신호 상태 분리를 구현했다. 기존 guest pthread/fork/exec
경로에 객체 수명을 연결했고, 실제 kernel CLONE_VM 프로세스에서 공유·비공유를
검증했다. **guest shared-VM clone과 posix_spawn 전체를 활성화한 단계는 아니다.**

## 구현

메모리 주소 공간의 공유와 signal disposition의 공유는 별개다. 기존 `signals.c`의
전역 action/가상 mask/wrapper 표시/exec 초기화 후보를 그대로 쓰면 CLONE_VM만
사용하는 자식의 sigaction이 부모의 논리 상태까지 바꿀 수 있다.

이제 `dl_thread.sighand`가 해당 task의 `dl_sighand` 객체를 가리킨다. 객체에는
action, wrapper 표시, exec 후보 집합, 잠금과 참조 수가 들어간다.

- CLONE_SIGHAND인 현재 pthread 경로는 객체를 공유하고 참조를 추가한다.
  생성 실패, 실제 task 소멸 확인 후 회수, COW fork 자식의 다른 task 정리에서
  참조를 반환한다. 마지막 참조가 사라진 동적 객체는 unmap한다.
- `dl_sighand_copy`는 독립적인 객체와 잠금을 만든다. 원본 잠금은 논리 snapshot과
  kernel sighand 복사가 일치하도록 실제 clone까지 유지해야 한다. 실패 시 새
  객체를 게시하지 않는다. 이 복사 API는 아래 구성요소 검사에서 사용하며,
  guest shared-VM dispatcher 연결은 아직 남아 있다.
- sigaction/query, guest signal 진입과 exec reset은 현재 task의 객체를 사용한다.
  기존 fork의 lock 순서는 유지하되 action lock은 그 객체의 lock이다.
- mask/wait/context와 signal stack은 계속 task별이다. 신호 처리 방식의 공유와
  signal mask의 공유를 혼동하지 않는다.

## 검증 범위

[원시 결과 및 fixture hash](results/sighand-runtime-2026-09-25.json).
runtime SHA-256:
`1dde3bf7cdaecb41e6a8ec66213b50c26c8d8519c64d5a63c6f6e28b385409ed`.
Android 구성요소 binary SHA-256:
`67035442ec4394179af092a3e3332bfc322be36a0c2a3fdf0a56f1bc66486edb`.

`sighand-host-test.c`는 실제 `signals.c`와 kernel signal/clone을 사용한다.
task lookup, 메모리 복사와 lock은 host adapter로 제공하고 SIGSYS dispatcher나
guest ELF는 실행하지 않는다. 사용자 handler를 실제 호출하는 검사도 아니다.
다음 세 종류를 각각 64회 실행한다.

1. CLONE_VM: 자식 action 변경/exec reset 후 부모 handler·mask·flags·초기화 후보 보존.
2. CLONE_VM|CLONE_VFORK: 실제로 부모가 정지한 동안 독립 action table로 자식 실행.
3. CLONE_VM|CLONE_SIGHAND: 자식의 변경을 부모의 kernel/논리 조회 모두에서 관찰.

자식의 메모리 쓰기를 부모에서 확인하므로 COW fork로 통과시킨 검사가 아니다.
독립 table의 마지막 참조를 반환한 뒤 실제 mapping 소멸을 확인한다. RLIMIT_AS로
유도한 할당 실패와 kernel의 invalid-clone 거부 이후 부모 상태·회수도 검사한다.
Ubuntu ARM64 host와 Android 일반 앱 UID에서 **각각 192회** 모두 통과했다.
host 구성요소 검사는 Codex sandbox의 initial Seccomp=2/NoNewPrivs=1 아래에서 통과했다.

별도로 실제 Direct guest의 정적·동적 pthread, worker fork/exec 6개 host suite와
기존 signal exec-reset 회귀를 통과했다. Android 전체 묶음은 **26.977초,
OK (3 tests)**이며 loader top-level 실행 126회와 fixture 730개, runtime kernel
재진입 200회를 포함한다. 기존 signal/cancellation/thread/fork/FD exec/network/PTY
회귀도 통과했다. 이번에는 성능 벤치마크를 실행하지 않았다.

Native import/TLS/interpreter/type/relocation 감사와 APK 서명, 최종 canonical
Gradle 및 instrumentation 빌드가 통과했다(runtime 180 pass/1 optional skip,
image 4 pass). Android는 API 35 ARM64 redroid, 4 KiB, SELinux Disabled,
initial Seccomp=0/CapEff=0/TracerPid=0이다. stock enforcing/16 KiB 증거는 아니다.
Android MCP로 설치·실행·결과·정책을 확인했으며 이번 호출 실패는 없었다.
기존 raw Linux ABI를 사용해 새 라이브러리 문서 조회는 필요하지 않았고,
UI/외부 앱 작업이 없어 Context7/Replicant/codex_apps는 사용하지 않았다.

## 남은 C04 조건

현재 `guest.c`의 shared-VM spawn 거부와 COW vfork 호환 경로는 그대로다.
이 상태 분리는 실제 shared-VM 실행을 허용하기 위한 선행 변경이다.

프로세스별 task registry와 exec 선택, 공유 VM을 남긴 채 exec/exit한 task의
runtime allocation 회수, FD snapshot과 kernel clone의 일치, vfork 중 잠금 소유권,
SIGKILL/생성 실패에서 살아남은 프로세스의 복구가 남았다. 이후 실제 glibc
posix_spawn[p]의 file actions·signal attributes, Python subprocess와 shell을
검사해야 한다. 별도 주소 공간의 fork로 대체해 C04 완료를 주장하지 않는다.

## 재현

```sh
clang -O2 -Wall -Wextra -Werror tools/direct-linux/sighand-host-test.c \
  tools/direct-linux/syscall.S -o /tmp/direct-linux-sighand-test
/tmp/direct-linux-sighand-test

TERMINAL_ANDROID_NDK="$PWD/.android-sdk/ndk/28.0.13004108" \
  bash tools/iphone/android-clang.sh -O2 -Wall -Wextra -Werror -fPIE -pie \
  -Wl,-z,now,-z,noexecstack,-z,max-page-size=16384 \
  tools/direct-linux/sighand-host-test.c tools/direct-linux/syscall.S \
  -o /tmp/direct-linux-sighand-android.so
```

[ELF packaging](ELF.md#reproduction)의 probe 입력에 이 Android 구성요소 binary를
넣고 loader에는 현재 runtime을 넣는다. baseline 비교 slot과 동시에 사용하지 않는다.
instrumentation class 목록에 `dev.terminal.runtime.directlinux.DirectLinuxSighandTest`,
인자에 `-e directLinuxSighand true -e directLinuxSighandSha256 COMPONENT_SHA256`를
추가한다. 이 APK는 일반 제품 APK에 배포하지 않는 opt-in 검사 전용이다.
