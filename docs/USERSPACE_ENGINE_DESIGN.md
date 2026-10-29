# Userspace Linux 자체 엔진 설계

작성일: 2026-10-28. 2026-10-29 공개 바이너리 정적 분석과 실행 계약 추가.
상태: 구현 전 설계안.

목표는 Userspace Linux의 실행 엔진을 독립적으로 구현하여 Linux 프로그램의
호환성을 높이고 실행 시간·CPU·메모리 비용을 네이티브에 가깝게 만드는 것이다.
**새 엔진은 syscall 변환에 ptrace를 사용하지 않는다.** PRoot 개조, proot-rs 포크,
proroot 바이너리 통합, 중단된 Direct Linux 복원은 구현 경로에 포함하지 않는다.
타 프로젝트는 공개된 동작·실패 사례·설계 선택을 검토하는 자료로만 사용한다.

이 문서의 성능 수치와 지원 항목은 목표 및 검증 조건이다. 새 엔진의 구현이나
달성된 성능을 뜻하지 않는다. 제품의 현재 PRoot 경로는 [RUNTIMES.md](RUNTIMES.md)에
기록되어 있으며, 이번 설계로 실행 파일·설치 환경·기본 엔진을 변경하지 않는다.

내부 ABI, patch 공개/무효화, 신호 상태, exec handoff와 첫 probe 조건은
[실행 계약](USERSPACE_ENGINE_EXECUTION.md)에 정의한다. 참고 바이너리의 실제 관찰은
[proroot 정적 분석](PROROOT_STATIC_ANALYSIS.md)에 기록한다.

## 1. 설계 결정과 범위

일반 ARM64 명령어는 CPU에서 그대로 실행한다. 변환이 필요한 Linux syscall만
호출한 프로세스 내부에서 처리하고, 커널에 그대로 맡길 수 있는 작업은 직접
실행한다. 외부 tracer나 모든 파일 요청을 대신 처리하는 서버를 필수 경로로
두지 않는다. 엔진의 정확성 모델과 빠른 진입 방법을 분리한다.

우선 설계 후보는 **검증된 syscall 지점의 직접 연결 + seccomp TRAP 포괄 경로**다.
전자는 자주 쓰는 호출의 신호 비용까지 줄이고, 후자는 정적 ELF·직접 syscall·
아직 연결하지 않은 코드를 포괄한다. 두 경로는 같은 syscall 구현을 호출한다.
TRAP의 신호 호환성과 Android 정책 적합성은 초기 실험에서 해결해야 하는
채택 조건이며, 충족하지 못하면 진입 구조를 재설계한다. 미해결 상태로 배포판
기능만 쌓거나 PRoot fallback을 붙여 완료로 처리하지 않는다.

- 제품 범위: 기존 Userspace Linux 안의 엔진 교체. 네 번째 실행환경을 만들지 않는다.
- 1차 지원 목표: ARM64, 현재 minSdk 31 / targetSdk 35, Debian·Ubuntu·Arch Linux ARM,
  배포판 glibc, 동적·정적 ELF, 정적 PIE, raw syscall을 사용하는 Go/Rust 프로그램.
  musl 정적 ELF도 ABI 검사에 포함하고 동적 musl 배포판은 후속 확장으로 구분한다.
- 사용자에게 root, ADB, Shizuku, 커널 모듈, 플랫폼 서명, target SDK 하향을 요구하지 않는다.
- 기존 Native/Bionic·AVF, 세션 인터페이스, Environment별 패키지 DB와 home,
  Android capability broker의 권한 경계를 유지한다.
- 실제 root, 임의 mount/namespace/cgroup, systemd 부팅, 다른 ISA 실행은 1차 목표가 아니다.
  Android 커널이 제공하지 않거나 정책이 금지한 기능을 엔진 성능 문제와 구분한다.

## 2. 현재 경계와 비교 구현 검토

현재 저장소는 APK의 PRoot/loader와 guest faststat/stdio 라이브러리를 사용한다.
일부 호출은 이미 추적을 생략한다. 따라서 모든 syscall이 항상 ptrace 진입·반환
정지를 일으킨다는 가정으로 개선량을 계산하지 않는다. `RootlessSessionController`는
PTY를 소유하고 `NativeExecutableLoader`는 APK의 고정 실행 파일만 허용한다.
`RootlessStorageFormat`에는 native·emulated·legacy hardlink 형식이 존재한다.
새 엔진의 출발점은 이 제품 계약이며 기존 엔진 내부 ABI는 계승하지 않는다.

### 2.1 proroot

검토 대상은 [coderredlab/proroot의 고정 리비전](https://github.com/coderredlab/proroot/tree/c01039ab017448263a4ddbd9221ecb88e737553c)이다.
공식 README는 ptrace 없는 Android ARM64/glibc runtime과 launcher/runtime/bridge/
linker/stub-loader 구성을 설명한다. 해당 트리에는 README, LICENSE와 다섯 바이너리만
있고 구현 소스는 비공개다. 후속 사용자 요청으로 v1.2.8의 다섯 ELF를 hash 검증한
뒤 정적으로 분석했다. 남아 있는 심볼과 disassembly에서 직접 코드 패치와
seccomp TRAP 코드, glibc runtime과 별도 loader 경계를 확인했다.
원본 소스·모든 실행 경로·동시성은 복원하지 않았다. 실기기 동작 목록은 upstream
보고이며 이번 프로젝트의 재현 결과가 아니다. 세부 근거와 주소는
[정적 분석 기록](PROROOT_STATIC_ANALYSIS.md)을 따른다.

[Termux 제안 글](https://github.com/termux/termux-app/issues/5245)은 LD_PRELOAD와 ELF의
SVC 명령 패칭을 결합한다고 설명한다. 실제 바이너리에서도 패치 경로를 확인했지만
이 글 자체나 해당 경로의 존재를 전체 syscall 포괄성의 증거로 취급하지 않는다. 글의 네이티브 성능,
GPU 배속, 무비용 주장도 우리 성능 목표의 근거로 사용하지 않는다.

공식 [v1.2.8 릴리스 기록](https://github.com/coderredlab/proroot/releases/tag/v1.2.8)은
PLT 가로채기를 통과하지 않은 fortified libc 함수의 `/proc/self/exe` 누출과,
상대 dirfd `unlinkat`가 hardlink 에뮬레이션 정리를 놓친 결함을 기술한다.
여기서 얻는 설계 요구는 공개 함수 후킹만으로 포괄성을 선언하지 않는 것,
경로 변환과 FD·링크 수명을 한 규칙으로 처리하는 것이다. 바이너리를 도입하거나
비공개 구현을 추정해 복제하지 않는다.

정적 분석에서 확인한 RWX 요청, guest 신호에 개입하는 경로, 일부 maintainer helper의
조건부 성공 종료는 그대로 채택하지 않는다. 자체 엔진에는 W^X patch transaction,
guest/engine signal state 분리, 실제 패키지 결과 검증을 적용한다.

### 2.2 proot-rs

검토 리비전은 `cd918286fa600f395c30c2d8a5c12274912c35c6`이다.
실제 [process/proot.rs](https://github.com/proot-me/proot-rs/blob/cd918286fa600f395c30c2d8a5c12274912c35c6/proot-rs/src/process/proot.rs)는
자식의 `ptrace::traceme()`, `waitpid(__WALL)` 이벤트 루프와 syscall 재개를 사용한다.
launch 지점의 seccomp 활성화는 TODO로 남아 있다. 다른 파일에 seccomp 이벤트
코드가 있다는 사실만으로 동작하는 가속 경로라고 판단하지 않는다.

[process/translation.rs](https://github.com/proot-me/proot-rs/blob/cd918286fa600f395c30c2d8a5c12274912c35c6/proot-rs/src/process/translation.rs)는
진입·반환 단계와 오류 상태를 분리하고,
[process/event.rs](https://github.com/proot-me/proot-rs/blob/cd918286fa600f395c30c2d8a5c12274912c35c6/proot-rs/src/process/event.rs)는
`CLONE_FS`에 따라 filesystem 상태를 공유하거나 복제한다.
[filesystem/translation.rs](https://github.com/proot-me/proot-rs/blob/cd918286fa600f395c30c2d8a5c12274912c35c6/proot-rs/src/filesystem/translation.rs)는
경로 정규화·bind 치환·host 경로 역변환을 나눈다.
[execve/enter.rs](https://github.com/proot-me/proot-rs/blob/cd918286fa600f395c30c2d8a5c12274912c35c6/proot-rs/src/kernel/execve/enter.rs)는
tracee 인자와 레지스터를 바꾸어 loader를 실행하고,
[loader-shim](https://github.com/proot-me/proot-rs/blob/cd918286fa600f395c30c2d8a5c12274912c35c6/loader-shim/src/main.rs)은
`no_std` 로딩 코드를 분리한다.

참고할 점은 명시적인 상태 전이·공유 소유권·ABI 경계·경로 왕복 변환이다.
외부 tracer와 tracee 메모리 수정은 채택하지 않는다. Rust 채택 자체는 ptrace
왕복을 제거하지 않는다. 소스 검토만 수행했으며 이 리비전의 Android 실행이나
성능·전체 호환성을 검증한 것은 아니다.

### 2.3 대안 비교와 독립성

| 방식 | 이점 | 주요 비용·한계 | 이번 판단 |
| --- | --- | --- | --- |
| PRoot 계열 외부 추적 | raw syscall을 관찰하고 커널 실행을 중개 | 정지·재개, 원격 메모리, tracer 집중 | 비교 기준으로만 사용 |
| LD_PRELOAD만 사용 | 일반 함수의 낮은 진입 비용 | 정적 ELF·내부 심볼·직접 syscall 누락 | 단독 정확성 경로로 제외 |
| 검증된 SVC 직접 연결 | 호출 프로세스 안에서 변환, 신호 비용 감소 | 코드 식별, W^X, 동시 수정, JIT, 원래 PC 보존 | 우선 성능 경로 |
| seccomp TRAP | libc에 의존하지 않는 syscall 진입 | SIGSYS·signal frame·필터 의미, 호출당 신호 비용 | 초기 포괄 경로 후보 |
| USER_NOTIF broker | 별도 프로세스에서 요청 처리 | 동기 IPC, FD 전달, 취소 경쟁, 자원 비용 | 기본 경로에서 제외 |
| 전체 CPU 동적 번역 | 명령 흐름과 syscall 통제 | 실행·코드 캐시·메모리 비용 | 1차 선택에서 제외 |
| namespace/chroot/FUSE/AVF | 커널 기능 활용 | 일반 APK의 필수 조건으로 보장 불가 | 별도 플랫폼 범위 |

독립 엔진의 syscall 명세·객체 모델·저장 형식·테스트는 새로 정의한다.
기존 프로젝트의 내부 ABI, 패치 묶음, loader를 가져와 새 이름을 붙이지 않는다.
Direct Linux는 [보존된 감사 기록](../tools/direct-linux/COMPATIBILITY_AUDIT.md)의
신호·exec·정책 검증 누락을 참고하는 정도로 한정한다. 과거 성공 수치를 새 엔진의
성능 근거로 쓰거나 당시 미완료 작업 목록을 새 구현 계획으로 승계하지 않는다.

## 3. 엔진 구성과 syscall 경로

```text
기존 Runtime service / Environment owner / PTY
  └─ APK 소유의 새 launcher와 exec bootstrap
      └─ guest 프로세스
          ├─ ARM64 일반 명령어 ─────────────── CPU
          ├─ 의미가 동일한 syscall ─────────── Android 커널
          ├─ 검증된 syscall 지점 ──┐
          └─ 나머지 변환 호출 ─ TRAP ─┤
                                    v
                      프로세스 내부 syscall dispatcher
                       ├─ VFS / FD / 가상 자격 증명
                       ├─ process / signal / memory
                       └─ 고정 host syscall gate ── Android 커널
```

커널 진입·block으로 인한 문맥 전환·파일시스템 비용은 남는다. 제거하는 것은
syscall 변환 때문에 외부 tracer를 깨우고 게스트를 멈추는 왕복이다.

### 3.1 모듈 계약

| 구성 요소 | 소유하는 상태와 책임 |
| --- | --- |
| APK launcher | 고정 실행 정책, capability probe, host가 결정한 launch manifest |
| ELF/exec | ELF·PT_INTERP·auxv·stack·TLS 초기화 경계, 실행 교체와 실패 원자성 |
| 진입 계층 | ARM64 레지스터 저장, syscall 번호/인자/원래 PC 수집, 결과 복구 |
| syscall core | 검증·부작용·errno·재시작 규칙. 진입 방식에 독립적인 하나의 의미 구현 |
| task 모델 | VM/FD/FS/signal/credential 공유 객체, thread·fork·exec 수명 |
| VFS | guest 경로·bind·가상 inode/소유권·hardlink·열린 파일 수명 |
| kernel adapter | Android에서 허용된 실제 syscall, feature별 오류와 대체 의미 |
| 제품 adapter | 기존 SessionController·설치·DNS·공유·백업 계약 연결 |

초기 언어 후보는 freestanding Rust `no_std` core와 작은 AArch64 assembly 경계다.
guest 진입 후 Bionic/glibc TLS, libc allocator, JVM, Rust `std`에 의존하지 않는다.
signal 경로의 할당·락·panic/unwind를 제한하고 `unsafe`는 ABI·메모리 접근 경계에
모은다. 언어 선택은 G0의 코드 크기·TLS·재진입 검증 후 확정하며 속도 근거로 삼지 않는다.

### 3.2 syscall 분류

syscall마다 입력 포인터, 상태 소유자, 실제 부작용, 반환 보정, blocking 여부,
취소·재시작, fork/exec 영향, Android 제약, 테스트를 명세한다.

1. **직행:** 검증된 일반 FD의 read/write, 일부 futex·clock 등 의미가 동일하고
   엔진 상태 갱신이 필요 없는 경우. 특수/내부 FD나 가상 상태를 다루는 경우는 제외한다.
2. **내부 변환:** open/stat/경로 변경, identity, proc, FD 생성·복제·종료 등.
3. **수명 전이:** clone/fork/vfork/exec, signal, mmap/mprotect/munmap 등.
4. **미지원/금지:** 실제 커널·정책과 호환성 명세에 맞는 오류. 성공으로 위장하지 않는다.

새로운 syscall을 무조건 직행시키지 않는다. 반대로 지원 가능한 syscall을
일괄 ENOSYS로 처리해 애플리케이션이 우회 경로를 타는 것을 호환성 완료로 세지 않는다.
`io_uring`처럼 요청 안에 별도 파일 작업을 싣는 인터페이스도 포괄성 검토 대상이다.

### 3.3 직접 연결과 포괄 경로

직접 연결은 로딩된 실행 코드의 검증된 syscall 지점에서 공통 dispatcher로
분기하는 방식이다. 디스크의 배포판 ELF나 libc를 덮어쓰지 않는다. 공개 함수
후킹은 추후 보조 최적화일 뿐 지원 여부를 결정하지 않는다.

- ELF 실행 구간, 명령 경계와 원래 bytes를 확인한다. SVC byte pattern 검색만으로
  데이터까지 수정하지 않는다. 증명할 수 없는 지점은 TRAP 경로에 남긴다.
- 각 지점은 원래 PC·레지스터·복귀 주소를 보존한다. 분기 거리, ASLR, BTI/PAC,
  instruction cache, 오류 반환과 cancellation 지점의 의미를 검사한다.
- 처음에는 guest 실행 전 확정할 수 있는 지점만 연결한다. 실행 중 코드 변경은
  관련 thread의 안전한 정지와 W^X 전환을 입증하기 전까지 하지 않는다.
- mmap/mprotect/dlopen/JIT, 코드 재사용·변경·unmap은 연결 세대를 무효화한다.
  JIT·자기 수정 코드를 단순히 훑었다고 포괄성을 주장하지 않는다.
- 캐시 키는 엔진 ABI·ISA·페이지 크기·ELF digest/구간/원래 bytes를 포함한다.
  mtime이나 파일명만으로 재사용하지 않고 크기 제한·회수 정책을 둔다.

포괄 경로는 변환할 raw syscall에 `SECCOMP_RET_TRAP`을 적용해 호출 thread에서
처리하는 후보다. Linux 문서상 TRAP은 원래 syscall을 실행하지 않고 SIGSYS를
전달한다. 자체 필터의 host gate 예외는 **상속된 Android 필터를 완화하지 않는다**.
필터 중첩에서는 우선순위가 적용된다.
[Linux seccomp 계약](https://docs.kernel.org/userspace-api/seccomp_filter.html).

다음 문제는 구현의 선행 조건이며 부록으로 미루지 않는다.

- 상속된 KILL 등 더 높은 우선순위의 정책은 자체 TRAP보다 먼저 효과를 낸다.
  그러한 raw syscall까지 TRAP이 잡는다고 가정하지 않는다. guest 실행 전의
  검증된 연결과 허용 syscall로의 정확한 의미 구현이 가능한지 확인하고,
  불가능한 프로그램·기기는 지원 공백으로 기록한다.
- guest SIGSYS handler/mask/wait/signalfd와 엔진 transport의 충돌.
- signal handler 안의 syscall, altstack, longjmp/setcontext, 수정된 ucontext,
  FPSIMD/SVE 상태, EINTR/SA_RESTART와 libc cancellation.
- guest seccomp의 nr/인자/PC 의미와 실제 host gate의 차이. guest 필터를 끄거나
  성공한 것처럼 무시하지 않는다. 지원 불가능한 필터는 명시적 결손으로 기록한다.
- exec 후 handler는 사라져도 필터는 상속되는 문제. 반복 exec 때 필터가 쌓이거나
  bootstrap이 handler 설치 전에 TRAP을 받지 않는 고정 gate 계약.
- 잘못된 guest 포인터를 EFAULT로 처리하면서 진짜 guest SIGSEGV/SIGBUS를
  삼키지 않는 접근 규칙. self process_vm 접근도 기기 허용 여부를 확인한다.

G0에서 이 구조가 필요한 호환성을 유지하지 못하면 전체 SVC 연결 등 다른 진입
방식을 새로 평가한다. 그때 일반 코드/JIT 포괄성과 추가 메모리 비용도 다시
검증한다. 신호 하나를 사용할 수 없게 만드는 조건을 숨겨 기본 엔진으로 승격하지 않는다.

## 4. ELF와 프로세스 의미

배포판 동적 링커를 가능한 한 그대로 실행한다. 자체 glibc 호환 동적 링커까지
새로 만드는 것은 초기 범위에서 제외한다. 자체 ELF loader는 ET_EXEC/ET_DYN,
PT_INTERP, static PIE, auxv/AT_EXECFN, stack/BSS/heap, TLS 진입과 executable
mapping을 책임진다. 16 KiB 정렬 APK가 4 KiB 정렬 guest ELF의 지원을 자동 보장하지
않으므로 실제 배포판 ELF·DSO로 페이지 크기별 로딩을 검증한다.

guest exec는 **APK bootstrap에 대한 실제 kernel exec**를 우선 후보로 삼는다.
커널의 thread 정리·PID/대기 관계·exec 효과를 최대한 보존하고, 사전 검증한
guest image 계획을 제한된 handoff FD로 전달해 새 주소 공간에서 적재한다.
app-data guest ELF 자체를 kernel exec 대상으로 삼지 않는다. bootstrap gate,
FD 전달, 필터 상속의 성립 여부는 G0에서 입증한다.

- commit 전 argv/env/shebang/FD 기반 실행을 검증하고 실패하면 원래 프로그램을
  유지한다. commit 이후 회복 불가능한 실패는 실제 종료로 보고하며 이전 실행을 복원했다고 하지 않는다.
- nonleader exec, 다른 thread의 종료, CLOEXEC, timers, rseq, robust-list,
  clear-child-TID, altstack, signal disposition의 reset/preserve 표를 작성한다.
- `fork`는 커널 COW를 사용하되 `CLONE_VM/FILES/FS/SIGHAND/THREAD`는 각각의 공유
  객체로 모델링한다. PID 하나를 공유 여부의 대용으로 사용하지 않는다.
- vfork/posix_spawn을 일반 fork로 대체해 같은 의미라고 주장하지 않는다.
  child 준비가 parent 주소 공간·FD·TLS를 손상하지 않는 경계를 따로 검증한다.
- 열린 실행 파일 교체/삭제, concurrent write/truncate, execveat/fexecve,
  shebang+CLOEXEC와 실패 errno를 검사한다.
- 엔진 소유 mapping과 guest mapping을 구분하며 MAP_FIXED, mremap,
  DONTFORK/WIPEONFORK, 파일 EOF/SIGBUS, MAP_SHARED의 수명도 함께 처리한다.

## 5. 경로와 파일 상태

VFS는 guest root/bind 선택, cwd/dirfd, 이름과 inode, 열린 파일을 별개로 모델링한다.
문자열 prefix 치환만으로 `..`, 중첩 bind, absolute symlink, trailing slash,
AT_EMPTY_PATH, no-follow, 삭제된 cwd와 rename 경쟁을 해결했다고 보지 않는다.
경로 구성 요소를 directory FD로 고정하고 syscall별 마지막 구성 요소 규칙을 적용한다.
openat2는 필수 host 기능으로 두지 않으며 지원하는 resolve flag만 정확히 구현한다.

### 5.1 파일 데이터와 메타데이터

일반 파일 내용은 실제 host 파일에 둔다. open 뒤에는 실제 FD·page cache·mmap을
사용하여 read/write/fsync를 파일 서버로 전달하지 않는다. guest mode/uid/gid와
가상 inode/link 관계는 Environment별 versioned metadata로 관리한다.
가상 root는 Android UID나 실제 권한을 바꾸지 않는다.

host hardlink가 불가능한 기기를 기본 조건으로 설계한다. hardlink 그룹은
하나의 backing object와 여러 guest 이름으로 표현하며 `st_ino/nlink`, 열린 FD,
rename/unlink/replace, 마지막 이름 삭제 후 열린 파일의 생존을 함께 처리한다.
새 형식은 PRoot `.l2s` 내부 ABI를 재사용하지 않는다. 이름별 내용 복사로
hardlink를 흉내 내거나 nlink만 수정하는 구현은 허용하지 않는다.

변경은 Environment 안에서 namespace/metadata transaction으로 직렬화한다.
다른 Environment와 읽기·쓰기 데이터 경로는 이 락을 공유하지 않는다.
prepare journal → host mutation → metadata commit → 완료/회수 순서와
각 지점의 crash 복구 규칙을 먼저 정의한다. host rename과 sidecar 갱신이
자동으로 하나의 원자적 연산이 된다고 가정하지 않는다. 엔진 reader는 commit
경계를 따르고, fsync/디렉터리 fsync 성공 시 보장하는 내구성을 명세한다.
disk-full·프로세스 사망·중단된 패키지 작업에서 재생·rollback을 검증한다.

이 엔진 밖의 host writer가 metadata를 우회해 rootfs를 수정하는 동작은 관리
경로로 제공하지 않는다. 공유 폴더는 별도 adapter로 host 파일 의미를 보존하고,
지원하지 않는 가상 hardlink/소유권 변경은 명시적 오류를 낸다. 외부 변경이 가능한
공유 경로에 장기 positive/negative cache를 적용하지 않는다.

### 5.2 FD와 pseudo 파일

일반 guest FD는 가능하면 kernel FD와 같은 번호를 유지한다. 내부 pin/manifest
FD는 별도 소유권과 한도를 두고 guest가 우연히 닫거나 재사용하지 못하도록
close/close_range/dup/fcntl/SCM_RIGHTS/exec를 함께 중개한다. 내부·가상 FD가
직행 syscall로 새지 않는 분기 조건을 검증하기 전에는 FD 직행을 켜지 않는다.
낮은 RLIMIT_NOFILE, EMFILE, FD 재사용과 공유 FD table의 동시 close를 검사한다.

`/proc/self/exe`, fd/cwd, maps, auxv, socket 반환 경로는 guest 관점과 일치시킨다.
`/dev`, PTY, Unix pathname/abstract socket과 peer credential, inotify는 각각
데이터 경로와 상태 갱신을 명세한다. 긴 Unix 경로에 host prefix를 붙여 guest
허용 길이를 줄이는 방식은 피한다. 모든 `/proc`·`/sys`를 가상화했다고 주장하지 않고
지원 목록과 host 제한을 공개한다.

## 6. Android와 제품 통합

[Android 실행 정책](https://developer.android.com/about/versions/10/behavior-changes-10#execute-permission)은
target API 29 이상 앱의 writable app-home 파일에 대한 직접 execve를 제한한다.
[AOSP app_neverallows](https://android.googlesource.com/platform/system/sepolicy/+/refs/heads/main/private/app_neverallows.te)는
app-data execute_no_trans와 untrusted app hardlink 제약의 근거다.
[AOSP app.te](https://android.googlesource.com/platform/system/sepolicy/+/refs/heads/main/private/app.te)의
execmem 허용은 코드 매핑 후보의 근거일 뿐 모든 기기·파일의 실행 허가가 아니다.
설계 검증은 실제 앱 UID·현 target SDK·SELinux enforcing·상속된 seccomp 상태에서 한다.

launcher/host gate는 승인된 APK artifact다. guest mapping과 W^X 전환은 별도로
검증하고 거부된 실행 매핑을 정책 완화나 무조건 익명 복사로 우회하지 않는다.
프로세스 내부 엔진은 악성 guest와 host를 분리하는 보안 sandbox가 아니다.
권한 broker와 비밀은 guest 주소 공간에 두지 않으며 기존 Android 권한을 유지한다.

제품 연결은 다음 경계에만 추가한다. UI와 터미널 renderer는 엔진 종류를 알아야
할 필요가 없고 엔진 내부 설정을 사용자 옵션으로 늘리지 않는다.

- `RootlessDebianBackend` / `RootlessEnvironmentSetup`: 설치·복구·패키지 검증에도
  선택한 엔진을 일관되게 사용한다. 기존 클래스 이름 변경은 별도 필요가 있을 때만 한다.
- `RootlessSessionController`: launch adapter만 교체하고 PTY·attach/detach·명시적
  terminate·서비스 소유권을 유지한다. UI 재생성은 프로세스 종료 조건이 아니다.
- `NativeExecutableLoader`: 구현 단계에서 고정 APK 실행 이름과 artifact 검증을 추가한다.
  guest 변수나 다운로드 파일로 launcher를 교체할 수 없다.
- `RootlessEngineInfo`: 실제 engine/ABI/build와 capability 결과를 보고한다.
  PRoot 버전·가속 플래그를 새 엔진 정보로 재활용하지 않는다.
- DNS·USB·공유 폴더·백업은 기존 권한과 host-selected binding 계약을 지킨다.
  새 engine/storage 형식을 이해하지 못하는 백업 reader는 명시적으로 거부한다.

## 7. 호환성과 성능의 완료 조건

### 7.1 호환성

기준은 같은 기기·배포판·입력에서 현재 가속 PRoot가 성공하는 동작의 보존이다.
일반 ARM64 Linux는 syscall의 기대 의미를 확인하는 보조 기준으로 사용하되 Android
정책 차이를 분리한다. 알려진 PRoot 실패만으로 새 엔진 미구현을 정당화하지 않는다.

| 검증군 | 필수 시나리오 |
| --- | --- |
| ELF/ABI | glibc 동적·정적·PIE, musl 정적, Go raw syscall, 잘못된 ELF, 4/16 KiB |
| process/signal | fork·vfork·spawn·thread·nonleader exec, cancellation, altstack, guest SIGSYS, nested handler, filter 상속 |
| VFS/FD | symlink·hardlink·identity, dirfd/rename 경쟁, deleted cwd, FD 전달·재사용, disk-full, crash 복구 |
| 패키지 | 세 배포판의 새 설치, apt/dpkg·pacman 설치/업그레이드/삭제, maintainer script, 중단/재시도, DB 무결성 |
| 개발 작업 | Git/rg 대형 저장소, Python import/subprocess/venv, Node/npm, offline pip/npm, 빌드·테스트·SQLite·watcher |
| 대화형/통신 | PTY·job control·resize·Ctrl-C, TCP/UDP/DNS/Unix socket, 실제 Android DNS 정책 |
| 제품 수명 | UI detach/recreate, 두 Environment 동시 작업, 종료/취소, 서비스 사망, 고아 자식·FD·메모리 회수 |

기본 교체 조건은 필수군 회귀 0건이며, **호환성 개선** 주장은 기준 엔진에서
재현한 구체적 실패가 새 엔진에서 해결되고 회귀 검사를 갖춘 경우에만 한다.
정적 프로그램이 실행됐다는 사실만으로 동적 preload와 동등한 성능을 주장하지 않는다.

### 7.2 성능 예산 제안

아래 수치는 구현 전 제안하는 채택 예산이며 사용자에게 보장하는 실측치가 아니다.
baseline 확보 후 어려움이 발견되어도 조용히 목표를 낮추지 않고 근거와 변경 결정을 남긴다.

| 항목 | 제안 목표 |
| --- | --- |
| 엔진 syscall 추적 | ptrace attach/TRACEME/TRACE 및 변환 목적의 stop 0; 외부 syscall broker 왕복 0 |
| 순수 CPU / 큰 순차 I/O | 비교 가능한 native 대비 wall·총 CPU 각각 1.05배 / 1.10배 이하 |
| warm metadata/raw syscall 묶음 | 비교 가능한 native 대비 wall·총 CPU 1.5배 이하를 지향; TRAP 비용을 따로 보고 |
| 대표 전체 작업 | 가속 PRoot 대비 최소 두 종류의 중앙값 30% 이상 단축; 필수 작업별 중앙값·p95 5% 초과 회귀 없음 |
| 메모리 | 같은 작업의 전체 PSS가 가속 PRoot 이하; 엔진 private 증분 초기 목표 프로세스당 4 MiB, thread당 64 KiB 이하 |
| idle / 회수 | 엔진 자체의 주기 polling·wake lock 없음; 30분 idle에서 주기적 wakeup 없음; 반복 종료 뒤 자원 증가 추세 없음 |

native 비교는 가능한 경우 같은 기기·파일·작업의 APK-owned 직접 syscall fixture를
사용한다. 전체 glibc 앱에 동등한 native 실행이 불가능하면 비율을 N/A로 남긴다.
다른 CPU의 Linux, AVF의 다른 저장소, Bionic/glibc가 다른 빌드 결과를 동일한
native 기준처럼 섞지 않는다. root-only chroot 결과가 있다면 보조 실험으로 구분한다.

최소 10쌍 교대 실행, p95 판단에는 최소 30회/엔진을 확보하고 중앙값·분포·비교의
불확실성을 보고한다. thermal/주파수·cache·전원·페이지 크기·정책·payload hash를
기록한다. 실제 cold cache를 만들지 못하면 최초 실행이라고 표시한다.
네트워크 다운로드와 AI 모델 대기는 엔진 개선량에서 분리한다.

원인 계측은 직접 연결/직행/TRAP 횟수, 재귀·fallback 사유, path walk, exec 로딩,
kernel I/O, code cache 생성·miss를 구분한다. 시간 측정과 계측 실행을 나누고
모든 자식·helper의 CPU와 PSS를 합산한다. shared page를 RSS 합계로 중복 계산하지
않는다. 전력 센서가 없으면 CPU·wakeups를 대리 지표로만 보고 배터리 개선을 단정하지 않는다.
`TracerPid=0` 한 번의 관찰만으로 무추적을 입증하지 않고 artifact의 진입 경로와
런타임 계측을 함께 검사한다. guest 디버거의 의도적인 ptrace는 엔진 추적과 구분한다.

## 8. 구현 순서와 중단 조건

이번 요청은 설계까지다. 아래 단계는 후속 구현의 진행 조건이며 현재 실행하지 않는다.

| 단계 | 산출물 | 다음 단계 조건 |
| --- | --- | --- |
| G0 진입 가능성 | 새 최소 APK probe, 직접 연결/TRAP/host gate, exec bootstrap, signal/filter 교차 검사 | 일반 enforcing ARM64의 현재 지원 OS·4/16 KiB 대상에서 핵심 계약 성립. 정책·signal·exec 모순이 있으면 진입 구조 재설계 |
| G1 의미 모델 | 독립 syscall 명세·task/FD/VM 모델, ELF와 동적 링커, process/signal 회귀군 | raw/static/dynamic 실행, fork/thread/exec·신호가 함께 동작. 일반 명령 성공만으로 통과 금지 |
| G2 파일과 패키지 | 자체 VFS/metadata/journal, 세 배포판의 독립 시험 환경 | hardlink·identity·패키지 관리·crash 복구·공유 경계 통과 |
| G3 비용 감소 | 검증된 지점 직접 연결 확대, bounded cache, 병목 계측 | 호환성 보존 및 native/가속 PRoot 성능·자원 예산 충족 |
| G4 제품 연결 | engine launch adapter, 설치·세션·백업·진단·이행 도구 | 수명·취소·동시성·원본 보존과 필수 workload 전체 통과 |
| G5 기본 교체 | 재현 가능한 APK artifact, 검증표와 전환 문서 | 지원 기기 행렬·호환성 개선 사례·전체 성능 근거 확인 후 별도 릴리스 절차 |

G0는 최소 두 제조사의 enforcing 실기기를 포함하는 검증 행렬로 계획한다.
필요한 OS/페이지 크기 기기가 없으면 해당 행은 미검증으로 남긴다. redroid나
SELinux disabled 성공은 개발 피드백이며 제품 조건 통과를 대신하지 않는다.
개별 구현 단계에서는 native unit/static analysis·실제 syscall differential test를
먼저 하고, Android/runtime 통합 변경 시 저장소의 canonical Gradle 검증과
android MCP 런타임 검사를 수행한다. UI 상호작용 변경이 생길 때만 replicant를 추가한다.

큰 초기 위험은 SIGSYS/guest filter 의미, exec 뒤 gate 수명, 16 KiB guest ELF,
공유 상태의 signal 재진입, metadata crash 원자성이다. 이들은 일반 구현 과제와
플랫폼 차단을 분리해 기록한다. near-native라는 이유로 호환성·내구성 검사를 생략하지 않는다.

## 9. 기존 환경 이행과 복귀

개발은 새 시험 Environment에서 수행한다. 기존 rootfs·home·패키지 DB·storage
marker를 수정하지 않는다. 엔진 선택과 storage format version은 별도 정보로
저장하며 살아 있는 Environment의 엔진을 바꾸지 않는다.

새 저장 형식을 도입할 때는 정지한 원본을 읽어 **새 Environment로 복사·변환**한다.
legacy/emulated/native link 관계, UID/GID·mode, rootfs와 package DB의 짝을
검증하고 journal 및 원자적 activation으로 완료한다. 원본은 그대로 둔다.
새 엔진이 legacy 형식을 직접 지원하는 선택도 별도 reader와 동등성 검사를
통과해야 하며 marker만 바꿔 지원되는 것처럼 만들지 않는다.

복귀는 보존한 원본 Environment를 다시 여는 것이다. 새 환경에서 발생한 변경을
옛 엔진 저장소로 자동 역변환하지 않는다. 실행 실패 후 같은 명령을 PRoot나
AVF에서 몰래 재실행하지 않는다. PRoot는 비교·기존 환경 운영을 위해 남을 수
있지만 새 엔진의 런타임 의존성이나 syscall fallback으로 포함되지 않는다.
