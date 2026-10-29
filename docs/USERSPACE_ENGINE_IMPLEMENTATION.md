# 자체 Userspace 엔진 구현 현황

2026-10-29. [설계](USERSPACE_ENGINE_DESIGN.md)의 실제 구현과 검증을 기록한다.
최종 목표는 **기존 가속 PRoot보다 높은 호환성과 전반적인 Linux·실사용 성능의 우위**다.
현재는 S0/S1의 첫 실행 경로를 구현했으며 G0도 아직 완료하지 않았다.
제품의 현재 엔진과 설치된 Environment는 바뀌지 않았다.

## 1. 구현된 코드

소스는 [독립 workspace](../native/userspace-engine/README.md)에 있으며 외부 Cargo
의존성이 없다. 필요한 네 crate부터 추가했고 launcher와 미완료 subsystem의 빈
stub은 생성하지 않았다.

| 구성 | 실제 동작 | 아직 포함하지 않는 것 |
| --- | --- | --- |
| `ue-abi` | RawSyscall/RawReturn·음수 errno·ARM64 번호 | handoff wire codec, 전체 syscall 명세 |
| `ue-core` | ELF64 ARM64 header/PT_LOAD/PT_INTERP 검증, credential query, signed branch encoding | 실제 ELF mapping·stack·동적 링커 진입, VFS/FD/task 모델 |
| `ue-platform` | raw host gate, SIGSYS kernel layout, exact-PC BPF, cache 동기화 | 일반 guest 필터·신호·메모리 접근 가상화 |
| `ue-runtime` | 직접 연결과 SIGSYS가 공유하는 단일 thread probe dispatcher | 일반 프로그램 실행, clone/exec·nested signal·longjmp |

ELF 검증은 파일 범위·overflow·machine/type·segment 크기·alignment·entry·interpreter를
확인한다. 현재 mapping 후보는 host page congruence를 요구하므로 4 KiB 전용 ELF를
16 KiB 호스트에 적재할 수 있다고 주장하지 않는다. parser 통과는 실행 통과가 아니다.

직접 연결은 엔진이 만든 작은 AAPCS syscall wrapper에만 적용한다. RW mapping에서
SVC를 검증한 B 명령으로 바꾸고 relay를 만든 뒤 cache 동기화·RX 전환을 완료한다.
디스크 ELF나 실행 중 guest 코드를 바꾸지 않는다. wrapper의 x16 scratch·유효 stack
전제는 임의 raw SVC에 적용할 수 없다. 원래 guest PC/SP 복원도 후속 구현 항목이다.

TRAP은 정확한 64-bit host gate PC와 architecture를 검사한다. kernel ucontext의
반환값만 바꾸고 이미 SVC 다음을 가리키는 PC를 다시 증가시키지 않는다. signal
extension 영역을 복사하거나 초기화하지 않고 kernel이 복구하도록 유지한다.
현재 SIGSYS 처리는 probe 전용이며 guest SIGSYS handler를 지원하는 구현이 아니다.

## 2. 확인한 결과와 한계

정확한 artifact hash와 결과는
[실행 기록](../native/userspace-engine/tests/results/entry-20261029.json)에 있다.

| 검사 | 결과 |
| --- | --- |
| `tools/userspace-engine/test.sh` | 통과: Rust unit 11개, kernel UAPI layout 대조, ELF 검사, ARM64 실행 |
| ELF artifact | Linux/Android target 모두 static ARM64; INTERP/DYNAMIC/TLS/미해결 symbol 없음; WX segment 없음; PT_LOAD 16 KiB 정렬 |
| 호스트 ARM64 실행 | 통과: 직접 연결/TRAP 반환, 가상 credential query, pipe write/read, EFAULT, GPR/NZCV/FPSIMD 표본, TRAP 계수 |
| 다른 seccomp 필터 중첩 | 통과: host gate도 추가 필터의 getpid EPERM 적용; 필터 우회 없음 |
| Android target build·별도 APK | 통과: minSdk 31/targetSdk 35, APK 설치 및 Zygote instrumentation에서 실행 |
| Android 내부 fixture | redroid API 35 / 4 KiB에서 통과 |
| Android 실행 전제 검사 | **실패**: redroid는 앱에서도 initial seccomp mode 0; runner가 code 1로 거부 |
| SELinux enforcing·16 KiB 기기 | 미검증: 연결된 redroid는 SELinux Disabled / 4 KiB |
| 기존 엔진 대비 호환성·실사용 성능 | 미검증: 배포판 실행과 비교 workload가 아직 없음 |

호스트의 초기 seccomp mode는 2지만 Android 앱의 필터와 동일한 정책이라는 뜻은
아니다. redroid의 inner `RESULT status=pass`만으로 Android 조건을 통과시키지 않는다.
일반 register·vector 전체와 SVE, 비표준 SP, BTI/PAC, guest 신호 충돌은 표본 검사
범위 밖이다. APK의 16 KiB ELF 정렬도 실제 16 KiB 실행을 대신하지 않는다.

10,000회 getpid의 native/connected/TRAP 시간은 진입 비용을 구분하는 진단 값이다.
단일 표본·고정 순서이므로 연결 경로가 native보다 빠른 표본이 나와도 성능 우위로
해석하지 않는다. host와 redroid의 수치를 섞지 않으며 CPU/PSS/p95/전력·패키지 작업·
터미널 반응성·가속 PRoot 비교가 필요한 완료 조건은 유지한다.

## 3. 실행 계약별 남은 구현

| 계약 | 현재 증거 | 다음 필요한 작업 |
| --- | --- | --- |
| E01 | fixture 반환·I/O·errno 및 raw TRAP register 표본 | 일반 entry의 register/PC/SP, 부작용 상태 모델 |
| E02 | 동적으로 번호를 전달하는 알려진 wrapper | 정적 guest ELF, 임의 SP·미지 SVC 포괄 |
| E03 | 없음 | bootstrap·handoff·실제 exec·nonleader·1,000회 반복 |
| E04 | transport SIGSYS 반환만 확인 | guest action/mask/altstack·nested handler·longjmp |
| E05 | 정상 pipe I/O와 EFAULT | blocking·signal interrupt·재시작·부분 완료·취소 |
| E06 | 자체 gate와 별도 deny 필터 중첩 | Android 상속 정책, KILL 우선순위, guest filter 의미 |
| E07 | 소유한 실행 전 mapping의 RW→RX 및 branch 범위 | 실패 주입·세대 무효화·active relay 회수·cache 압박 |
| E08 | ELF parser와 artifact 정렬 검사 | 실제 ELF/DSO 적재·TLS/auxv/BSS/SIGBUS 및 4/16 KiB 기기 |

VFS·hardlink·metadata/journal, process-shared FD/FS/VM, 패키지 설치/복구,
서비스·PTY·취소·백업 연결은 구현되지 않았다. 첫 probe 통과를 최종 목표나 G0의
완료로 바꾸지 않는다. 다음 구현은 실제 ELF/stack 적재와 bootstrap exec 계약이며,
그 과정에서 signal/entry의 필수 결손을 함께 해결한다.

## 4. 구현 결정과 설계 변경 기록

현재까지 큰 방향 변경은 없다. ptrace 없는 내부 처리, 공통 의미 구현, 직접 연결과
TRAP, 독립 ELF/저장 형식·제품 adapter 방향을 유지한다.

- **I001 — fixed ET_EXEC probe:** gate PC와 bootstrap 후보를 실제 실행하기 위해
  두 Rust target의 freestanding probe를 fixed ET_EXEC로 빌드했다. 이는 제품의
  최종 ELF 형태·고정 주소·ASLR 계약을 확정한 결정이 아니다. 실제 exec 반복과
  enforcing Android 검증을 거쳐야 한다.
- **I002 — no_std의 추가 의존성 검사:** 초기 atomic read-modify-write가 compiler
  outlined helper를 통해 `getauxval`을 요구해 링크 실패했다. 현재 단일 thread와
  SIGSYS 단일 writer 조건에 맞춰 atomic load/store로 구현하여 불안정한 compiler
  feature flag나 가짜 libc symbol 없이 해결했다. 다중 thread 동기화의 해결로
  일반화하지 않으며, 이후에도 artifact의 미해결 symbol·TLS·init 의존성을 검사한다.
- **I003 — APK 전제 실패를 분리:** APK-owned 실행 파일이 동작해도 상속 필터 조건이
  없으면 runner가 실패하도록 했다. redroid의 편의 조건을 제품 지원 범위로 확대하지 않는다.

정확성·성능·안정성의 여러 이점을 위해 큰 설계 변경이 필요해지면 여기에서 기존
선택·새 선택·측정 근거·이행 영향·잔여 위험을 함께 기록한다.

## 5. 검증 환경과 도구 제한

Android MCP로 대상 상태, APK 설치와 instrumentation 결과를 확인했다. incremental
install은 기기에서 거부됐지만 도구의 streamed install fallback은 성공했다.
Context7은 월간 할당량 초과로 사용할 수 없어 설치된 Linux UAPI와
[Linux seccomp 문서](https://docs.kernel.org/userspace-api/seccomp_filter.html)를 참조했다.
Jev로 비공개 저장소 내용을 전송하는 검토는 이전 자동 승인 제한에 따라 수행하지
않았으며 source/diff와 실제 검사 결과를 로컬에서 검토했다.

설치된 Rust 1.89에는 rustfmt가 없어 자동 format 검사는 실행되지 않았다.
standalone APK 빌드에서는 기본 AAPT2 wrapper의 캐시 부재와 시스템 AAPT2의 새 SDK
불일치를 확인한 뒤, 이미 설치된 같은 버전의 AAPT2 artifact를 명시적으로 지정했다.
저장소의 SDK/Gradle 설정이나 시스템 패키지는 바꾸지 않았다. 제품 Android/runtime/
image 코드를 수정하지 않았으므로 canonical Gradle 빌드는 이번 변경에 적용하지 않았다.
