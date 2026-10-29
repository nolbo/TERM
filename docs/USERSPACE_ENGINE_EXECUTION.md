# 자체 Userspace 엔진 실행 계약

2026-10-29. [기본 설계](USERSPACE_ENGINE_DESIGN.md)의 구현 전 세부안이다.
[proroot 정적 분석](PROROOT_STATIC_ANALYSIS.md)은 문제를 구체화하는 근거이며
아래 객체·ABI·상태·테스트는 자체 엔진에 새로 정의하는 계약이다. 아직 구현된 API가 아니다.

## 1. 구성과 공통 ABI

논리적으로 host launcher, freestanding exec bootstrap, freestanding syscall core를
분리한다. 초기 APK 실행 허용 목록에는 launcher와 bootstrap만 추가하는 방향으로
설계한다. core는 APK에 고정된 코드이며 guest libc의 LD_PRELOAD 로딩 성공에
의존하지 않는다. 세 부분을 몇 ELF로 패키징할지는 주소·재배치·PSS probe로 결정한다.
proroot의 다섯 바이너리 구성을 그대로 채택하지 않는다.

host launcher만 Android/Bionic 및 제품 서비스와 연결한다. guest 진입 뒤 core와
bootstrap은 glibc/Bionic TLS·libc malloc·stdio·동적 symbol lookup을 호출하지 않는다.
배포판 PT_INTERP를 사용하며 별도 glibc 호환 링커를 초기 구현 범위에 추가하지 않는다.

| 내부 객체 | 필수 정보 | 소유권 |
| --- | --- | --- |
| EngineAbi | 버전, ARM64 ABI, host page size, 기능 bitset, syscall-table revision | APK build 및 시작 시 검증 |
| SyscallFrame | 원래 nr, x0–x5, 원래 PC/SP, 진입 종류, 복귀 register image, call sequence | 한 thread의 한 번의 진입 |
| ThreadContext | TID와 생성 세대, frame stack, guest/engine signal state, scratch, 진행 중 부작용 | kernel task 수명 |
| ProcessContext | VM/FD/FS/sighand/credential 객체 참조, exec 세대, 종료 상태 | 각 clone flag에 따른 공유/복제 |
| MappingRecord | 주소·길이·보호·backing identity·세대·guest/engine 소유·fork 속성 | VM 객체 |
| PatchPlan | image digest/세대, 지점·원본 bytes·예상 ABI, relay, 원래 PC 대응, 검증 결과 | MappingRecord보다 짧거나 같은 수명 |
| ExecPlan | 고정 bootstrap ABI, image/인터프리터 FD, argv/env/auxv, guest cwd·자격 증명, 공유 객체 참조 | 준비 task에서 성공한 새 이미지로 인계 |

raw 진입은 ARM64 Linux의 성공값 또는 음수 errno를 반환한다. libc의 `-1/errno`
변환을 core에서 중복 수행하지 않는다. 내부 호출용 임시 레지스터와 guest가
보존을 기대하는 레지스터를 구분하고 vector/FPSIMD/SVE 상태 보존은 진입 adapter가
검증한다. syscall 지점을 일반 C 함수 호출의 clobber 규칙으로 취급하지 않는다.

ThreadContext 조회에 guest TLS를 사용하지 않는다. 초기 후보는 엔진 gate의 gettid와
세대가 있는 task table이다. clone 자식이 guest로 복귀하기 전에 등록하고 TID 재사용과
late event를 구분한다. signal 중 조회는 할당·blocking lock 없이 가능한 구조여야 한다.
이 조건을 입증하기 전에는 일반 pthread 지원을 선언하지 않는다.

## 2. syscall 의미와 부작용

명세 행 하나는 nr/이름, 인자와 copy 규칙, 참조 상태, host 연산, 반환 보정,
blocking/restart 특성, commit 지점, feature 요구, 테스트 ID를 가진다.
직행 가능 여부는 번호뿐 아니라 FD 종류·flags·가상 상태 의존성으로 결정한다.

호출 상태는 `Captured → Prepared → Issued → Completed → Returned`다.
입력 포인터 검증이나 경로 탐색 실패는 Issued 이전 오류다. Issued 이후 신호,
취소 또는 통신 오류가 생기면 원래 연산을 임의로 다시 실행하지 않는다.
close/rename/exec와 부분 write의 재시도 가능성을 같은 규칙으로 처리하지 않는다.

syscall별 포인터 의미를 보존한다. syscall이 원래 허용하는 partial copy/I/O와
EFAULT를 구분하며, 전체 버퍼를 먼저 읽는 방식으로 의미를 바꾸지 않는다.
진짜 guest SIGSEGV/SIGBUS를 포인터 검사 실패로 흡수하지 않는다.

함수 연결·SVC 연결·TRAP은 모두 같은 명세와 core로 들어온다. 경로별 별도
open/stat/identity 구현을 두지 않는다. 최적화는 공통 검증 결과를 사용하며
정확성 경로와 다른 errno·FD·파일 부작용을 만들면 비활성화한다.

## 3. PatchPlan의 검증과 공개

상태는 `Discovered → Validated → Prepared → Published → Retired`다.

1. **Discovered:** 실행 mapping과 원본 image identity를 확인한다. PF_X나 SVC
   byte pattern만으로 안전한 patch 지점이라고 판정하지 않는다.
2. **Validated:** decode한 명령 경계, 제어 흐름, live register, stack 사용,
   relocation, 원래 PC 복원 가능성을 검사한다. syscall 번호가 동적이면 원래
   x8을 그대로 전달한다. 주변 MOV를 보고 고정 번호라고 임의 추정하지 않는다.
3. **Prepared:** 원본 bytes 재확인, relay 범위와 code size 예산, 분기 도달성,
   BTI/PAC, 오류 rollback을 검증한다. 디스크 ELF는 변경하지 않는다.
4. **Published:** 아직 guest가 실행하지 않는 mapping에서 W^X를 유지하며 수정,
   architecture가 요구하는 cache 동기화, RX 복구를 완료한 뒤 한 번에 노출한다.
   중간 실패는 원본 복구 후 TRAP 경로를 선택하거나 guest 실행 전에 실패한다.
5. **Retired:** unmap/mremap/mprotect·파일 변경·exec로 mapping 세대가 바뀌면
   이전 plan을 재사용하지 않는다. 진행 중 frame이 참조하는 PC map/relay는 먼저 회수하지 않는다.

첫 직접 연결 대상은 저장 규약과 scratch 영역을 검증한 wrapper 경계다.
임의의 raw SVC 앞에서 guest SP 아래에 register를 저장하는 것은 안전하다고
가정하지 않는다. stack이 없거나 guard page에 닿는 코드, 임의 SP, redirection에
필요한 register 보존을 증명하지 못한 지점은 TRAP에 남긴다. 이는 속도 경로의
적용률 제한이며 raw syscall 지원 제외가 아니다.

실행 중인 기존 페이지를 수정하거나 dlopen/JIT의 실행 공개 시점을 통제하는
기능은 별도 동시성 계약을 통과하기 전까지 사용하지 않는다. 새 executable mapping은
TRAP으로 실행할 수 있어야 한다. 상속된 KILL 정책으로 TRAP조차 도달할 수 없는
syscall은 사전 연결·정확한 emulation 가능 여부를 판단하고 불가하면 명시적으로 거부한다.

code cache는 bytes/site 수뿐 아니라 touched page·private dirty page 예산을 가진다.
캐시 eviction은 active frame과 mapping generation을 참조한다. 첫 버전에서는
영속 실행 코드 cache를 두지 않고 APK 갱신 뒤 stale 코드를 재사용하지 않는다.

## 4. 신호와 필터

guest signal state와 engine transport state는 별도로 저장한다. 엔진이 SIGSYS를
사용하더라도 guest의 sigaction/mask/대기 결과를 성공으로만 위장하지 않는다.
engine 안에서 발생한 신호에 제공할 PC/SP/register context를 guest 원래 위치로
대응시키고 guest가 이를 수정한 뒤 돌아오는 경우도 처리해야 한다.

| 진입 상태 | 필요한 처리 |
| --- | --- |
| Guest 실행 중 | 일반 guest signal 의미 유지 |
| core의 짧은 상태 갱신 중 | 필요한 범위만 defer하고 잠금을 풀기 전에 guest callback을 호출하지 않음 |
| host blocking syscall 중 | guest mask·EINTR·SA_RESTART·cancellation에 맞춰 interruptible하게 대기 |
| guest handler에서 재진입 | 독립 frame과 scratch, 이전 부작용 상태 보존 |
| longjmp/setcontext/exec로 이탈 | 회수 가능한 임시 자원과 완료된 부작용을 구분; 사라진 C stack에 cleanup을 의존하지 않음 |

guest altstack을 엔진 전용 stack으로 몰래 대체하지 않는다. engine signal-frame
수명과 guest stack overflow/SS_AUTODISARM·중첩 handler를 함께 검증한다.
임의 SIGTRAP을 넘기거나 결과 0으로 바꾸는 일반 handler를 도입하지 않는다.

자체 BPF는 ABI/arch 확인, 고정 host gate의 정확한 syscall 위치, 필요 인자 조건,
진행 중인 guest의 nr별 진입 규칙을 분리한다. gate 예외는 자체 필터에만 적용된다.
guest 필터가 보는 nr/인자/PC와 실제 host 호출의 차이를 보존할 수 없다면 그
필터 기능은 명시적 미지원이며 전체 호환성 통과로 집계하지 않는다.
TRACE/USER_NOTIF를 호환성 fallback으로 숨겨 사용하지 않는다.

## 5. exec handoff

첫 후보는 동일 APK의 freestanding bootstrap으로 실제 kernel exec를 수행하고
그 안에서 guest image를 적재하는 것이다. 영구적으로 남는 자체 seccomp 필터 때문에
bootstrap이 handler 설치 전에도 사용할 수 있는 gate의 주소/호출 위치가 필요하다.
고정된 작은 bootstrap gate 영역과 그 영역으로 호출하는 재배치 가능한 core를
초기 후보로 시험한다. 영역 주소·크기·충돌 처리는 G0의 link/mapping 계약으로
확정하며 proroot bridge 주소를 복사하지 않는다. ASLR·MAP_FIXED·guest ELF 충돌을
검증하지 않은 고정 주소를 제품 ABI로 확정하지 않는다.

ExecPlan은 guest가 임의로 덮어쓸 수 있는 환경 변수 모음이 아니라 versioned,
길이 제한된 handoff 데이터다. host 권한이나 비밀은 담지 않는다. 후보 전달 수단은
봉인한 memfd이며 지원되지 않는 기기의 전달 방식도 동일한 불변성 검증을 거쳐야 한다.
공유 상태와 독립 파일 내용을 구분하고 가상 포인터를 그대로 직렬화하지 않는다.

상태는 `Validate → Pin → Prepare → KernelCommit → Bootstrap → GuestEntry`다.

- Validate: ELF/shebang/argv/env/FD 실행 의미·권한·저장 형식·page size 검사.
- Pin: 같은 실행 inode와 interpreter FD를 유지한다. 이름으로 다시 열어 대상이 바뀌지 않게 한다.
- Prepare: 새 stack/auxv/mapping 계획, 필요한 내부 FD와 CLOEXEC 처리, 실패 정리 준비.
- KernelCommit: kernel exec가 실패하면 원래 guest image가 계속 실행되며 errno를 반환한다.
  성공하면 다른 thread 정리와 kernel exec 효과를 받아들인다.
- Bootstrap: 상속 필터 아래에서 handoff 버전/길이를 검증하고 core·guest linker를 적재한다.
  매 exec마다 필터를 중복 추가하지 않는다. handler 준비 전 TRAP은 설계 실패다.
- GuestEntry: 주 ELF/링커 PC로 이동하고 handoff scratch/불필요 FD를 회수한다.
  성공한 kernel exec 뒤 실패는 새 프로세스의 실제 실패로 기록한다.

kernel exec 성공 전에 모든 후속 mapping 성공을 보장할 수는 없다. 따라서 사전
검증 오류와 commit 뒤 치명적 실패를 구분한다. guest `/proc/self/exe`, auxv, process
name, signal·timer·rseq·robust-list reset 결과는 reset/preserve 표로 관리한다.
nonleader exec와 vfork child의 준비에는 일반 heap/lock을 그대로 사용하지 않는다.

## 6. 파일 상태의 최소 객체 모델

`FdTable`과 `OpenDescription`을 분리한다. dup·fork·SCM_RIGHTS로 공유되는 offset과
status flags는 OpenDescription에, FD 번호·CLOEXEC·세대는 FdEntry에 둔다.
`CLONE_FILES`와 `CLONE_FS`는 각각 FdTable과 cwd/root/umask 객체의 공유를 결정한다.
서로 다른 PID라는 이유로 FD table이 독립이라고 판단하지 않는다.

내부 FD는 별도 registry로 관리하되 guest-visible 번호 공간과 충돌하지 않는
할당·relocation·EMFILE 규칙을 검사한다. BPF는 변경 가능한 FD 객체 종류를
조회할 수 없으므로 숫자 범위 조건만으로 stale FD를 안전하다고 가정하지 않는다.
직행 read/write를 켤 범위가 입증되지 않으면 같은 프로세스의 dispatcher에서 검사한다.

가상 파일은 `ObjectId + generation`, 이름은 `parent ObjectId + name`, 열린 파일은
OpenDescription으로 식별한다. 경로 cache를 identity의 원본으로 삼지 않는다.
hardlink 이름은 같은 ObjectId를 참조하며 마지막 이름 제거 뒤에도 열린 참조를 유지한다.
별도 프로세스의 metadata reader/writer는 process-shared 동기화와 journal 세대를
사용한다. 프로세스 안의 Rust 참조계수만으로 Environment 전체 상태를 공유하지 않는다.

metadata transaction은 prepare 기록에 대상 identity·예상 세대·연산 의도를 남기고,
host mutation과 metadata commit 사이의 crash 상태를 구별해야 한다. 복구는 이름만
보고 rename/unlink를 재실행하지 않으며 inode/세대와 기록된 결과로 판정한다.
writer 사망은 손상된 shared lock을 해제하는 것만으로 끝내지 않고 journal을 복구한다.
정확한 on-disk encoding은 G2 시작 전에 freeze하고 여기에 정의한 회복 규칙으로 검증한다.

## 7. 첫 probe의 구체적 통과 조건

G0는 단순 open 속도 시험으로 끝내지 않는다. 아래 경우를 새 최소 구현으로 시험하며
현재 환경·proroot 바이너리를 guest로 실행하는 것으로 대체하지 않는다.

| ID | 입력/상황 | 관찰할 결과 |
| --- | --- | --- |
| E01 | 동일 syscall을 직접·연결·TRAP으로 수행 | bytes·errno·레지스터·부작용 동일; 변환 목적 ptrace 0 |
| E02 | 동적 x8, 정적 ELF, 비표준 SP, 미지 SVC 패턴 | 연결 가능 여부와 무관하게 포괄 경로의 결과 정확; 임의 코드 변경 없음 |
| E03 | exec 1,000회, nonleader exec, 잘못된 image/FD | 필터·FD·메모리 증가 없음; precommit 실패는 기존 이미지 유지 |
| E04 | guest SIGSYS/SIGTRAP, altstack, handler 안 syscall, longjmp | 신호 action/mask/PC·pending 의미 보존; 자동 성공·신호 삼킴 없음 |
| E05 | FIFO/pipe/socket/lock 대기 중 signal·취소 | 정상 EINTR/재시작·부분 완료, 중복 부작용과 engine lock 교착 없음 |
| E06 | stricter inherited filter 및 guest filter | host gate도 기존 정책 적용; 지원 불가를 성공으로 위장하지 않음 |
| E07 | RW→RX 실패, mapping generation 변경, cache 압박 | 원본 보존·명시적 오류 또는 안전한 TRAP; RWX/proc-mem fallback 없음 |
| E08 | 4/16 KiB host에서 실제 배포판 ELF/DSO | segment·BSS·TLS·auxv·SIGBUS 의미 확인; APK 정렬만 검사해 통과 금지 |

G1은 clone 공유 조합·부작용·signal의 통합 검증, G2는 crash/disk-full·실제 패키지
결과 검증을 추가한다. ldconfig를 포함한 보조 명령의 종료 코드만 확인하지 않고
배포판 linker/cache·설치 파일·의존성·실행 결과를 검사한다. 서비스 시작 제한이
필요하면 명시적인 배포판 정책으로 제공하고 syscall core에 명령명별 성공 처리를 넣지 않는다.

측정은 연결률·TRAP 횟수·instruction-cache/private dirty page·exec 준비 시간과
전체 CPU/PSS를 함께 기록한다. 선택적인 직접 연결이 실제 비용을 줄이지 못하거나
포괄 경로가 필수 호환성을 지키지 못하면 기본 엔진 승격을 중단하고 구조를 재검토한다.
