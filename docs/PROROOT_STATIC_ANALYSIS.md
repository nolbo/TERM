# proroot v1.2.8 정적 분석

2026-10-29. 사용자 요청에 따라 공개 ELF를 정적으로 분석했다. 목적은
[자체 엔진 설계](USERSPACE_ENGINE_DESIGN.md)의 진입·실행·호환성 계약을 구체화하는 것이다.
아래는 파일과 명령어에서 관찰한 사실 및 그 한계다. 원본 소스 복원,
런타임 실행, 성능 측정, 모든 경로의 형식 검증은 수행하지 않았다.
복원한 코드나 바이너리를 제품 구현에 포함하지 않는다.

## 1. 분석 대상과 재현

원본은 [공식 v1.2.8 릴리스](https://github.com/coderredlab/proroot/releases/tag/v1.2.8)의
다섯 asset이다. 실제 다운로드 크기와 SHA-256이 릴리스 본문 및 GitHub asset
digest와 모두 일치했다. `.so` 확장자가 모두 shared library라는 뜻은 아니다.

| 파일 | bytes | SHA-256 |
| --- | ---: | --- |
| libproroot.so | 43,624 | `a4e74d75b66cdc02b080adfe863dbf9951c3b30610d77beddc95488d5fe5de01` |
| libproroot-runtime.so | 375,120 | `8c47a0a7db32d84c179ebb5bf3640f655a3181860ece5886ae44d92858730c34` |
| libproroot-bridge.so | 23,624 | `1c5bc9537a270e8bf8b1c70222813f57b60b828bfb5503ddf8fe37685092de2f` |
| libproroot-linker.so | 79,408 | `51a0ec5bfed00e572a0de09e22d9057e2befc386b78e426613d3e0ab03f4ecee` |
| libproroot-stub-loader.so | 144,144 | `06c6624db3bdc45b9ced151cd781df439a37b47731d244b93e9d6a58cd48cde0` |

실행하지 않고 `readelf -W -h -l -d -s`, `strings -a -t x`,
`llvm-objdump -d --print-imm-hex`로 헤더·심볼·정적 호출 관계·기계 명령어를 읽었다.
분석 중 파일은 임시 디렉터리에만 저장했다. 다음 명령으로 동일 artifact의
대표 경로를 재확인할 수 있다. 아래 명령은 ELF를 실행하지 않는다.

```sh
sha256sum libproroot*.so
readelf -W -h -l -d -s libproroot-runtime.so
llvm-objdump -d --print-imm-hex --disassemble-symbols=patch_phdr_callback,dynamic_syscall_trampoline libproroot-runtime.so
llvm-objdump -d --print-imm-hex --disassemble-symbols=proroot_install_exec_trap_seccomp,proroot_svc_patch_apply libproroot-stub-loader.so
```

주소는 ASLR 이후 절대 주소가 아니라 각 ELF의 link-time virtual address다.
파일 offset과 혼동하지 않는다. bridge는 ET_EXEC로 고정 주소를 갖는다.
심볼명·진단 문자열만 있는 경우와 실제 명령어 흐름을 확인한 경우를 구분한다.

## 2. 파일별 역할

| 파일 | 직접 관찰 | 구조 해석 |
| --- | --- | --- |
| launcher | ET_DYN, `/system/bin/linker64`, DT_NEEDED `libc.so`/`libdl.so`; fork·waitpid·execve·PTY foreground 처리 호출 | Android/Bionic 시작 및 자식 수명 경계 |
| runtime | ET_DYN, DT_NEEDED `libc.so.6`; libc API export, patcher, path hook, signal handler | glibc 프로세스 안의 호환성 처리와 코드 연결 |
| bridge | ET_EXEC, entry `0x70000049cc`, PT_INTERP/DT_NEEDED 없음; `trampoline_main`, `mmap_load_and_jump`, raw SVC | libc 동적 의존성 없이 실행을 연결하는 별도 이미지 |
| linker | ET_DYN, entry `0x260`, PT_INTERP/DT_NEEDED 없음, 일반 symbol table 없음; relocation/TLS/DSO 관련 진단 문자열 | 독립 동적 로딩 구현과 부합. 실제 relocation·TLS 지원 범위는 미확정 |
| stub loader | ET_DYN, entry `0xc00`, PT_INTERP/DT_NEEDED 없음; static loader, patcher, SIGSYS dispatcher와 filter builder | 정적 ELF 등 별도 실행 경로를 위한 로더/중개 코드 |

launcher의 `waitpid` 호출은 자식 종료·작업 제어에도 필요하다. 그 존재만으로
ptrace tracing이라고 분류하지 않는다. 반대로 ptrace import가 없다는 사실만으로
모든 raw syscall 경로에 추적이 없다고 증명하지도 않는다.

## 3. 확인한 명령어 경로

### A. 직접 코드 패치

runtime의 `patch_libc_syscalls` (`0x29880`)는 `dl_iterate_phdr`를 호출하며 callback은
`patch_phdr_callback` (`0x285a0`)이다. callback에는 SVC encoding `0xd4000001` 비교,
주변 명령어 검사, relay 할당, 분기 생성, 코드 저장과 instruction-cache flush가 있다.
`0x29174–0x291c0`에서는 26-bit 상대 offset의 범위를 검사하고 `0x14000000`을 합성하여
AArch64 B 명령을 저장한 뒤 `__clear_cache`를 호출한다. 별도 경로에는 BL 생성도 있다.

stub의 `proroot_svc_patch_apply` (`0x9a20`, size 3,012)는 실행 가능한 PT_LOAD를
대상으로 4-byte 단위 SVC 비교와 주변 명령어 검사를 수행한다. 단순 함수 export
목록만 사용하는 구조는 아니다. 다만 모든 AArch64 코드·JIT·정적 바이너리의
정확한 연결을 보장한다는 결론은 이 정적 분석으로 얻을 수 없다.

runtime의 `dynamic_syscall_trampoline` (`0x7f80`, size 92)에서 확인한 분기는
전달된 번호 56(openat)을 특별 처리하고 다른 분기에서 raw SVC를 실행한다.
다른 syscall에 별도 trampoline/handler도 있으므로 이 함수 하나를 전체
dispatcher로 해석하거나 모든 동적 syscall이 동일하게 가상화된다고 해석하지 않는다.

### B. 코드 변경 권한

runtime `0x28970–0x28988`은 protection 7을 넣어 `mprotect`를 호출한다.
stub `0x9af8–0x9b2c`에도 syscall 226(mprotect), protection 7 요청이 있다.
7은 READ|WRITE|EXEC다. stub의 `0x9e18–0x9e30`은 protection 5로 복구하는 경로다.
이것은 RWX **요청 코드의 존재**이며 실제 Android 정책이 허용한다는 증거가 아니다.

runtime은 `/proc/self/mem`을 여는 코드와 patch callback의 `pwrite` 분기를 갖는다
(`0x29888–0x298c0`, `0x28ab0–0x28ad4`). 따라서 모든 패치가 동일한 권한 전환 경로로
성공한다고 가정할 수 없다. 자체 엔진은 W^X와 허용된 매핑을 전제로 별도로 설계하고
실패한 보호 변경을 `/proc/self/mem` 쓰기로 보완하는 방식을 채택하지 않는다.

### C. 정적 실행에도 seccomp TRAP 코드가 존재

stub의 `proroot_install_exec_trap_seccomp` (`0x8b08`, size 232)는 no_new_privs
설정 후 11-entry BPF를 구성한다. 명령어에서 해석되는 필터는 host gate의
instruction pointer 하위·상위 32-bit 비교, execve(221)/execveat(281) 비교,
ALLOW `0x7fff0000`와 TRAP `0x00030000` 반환으로 구성된다.
`proroot_install_clone_trace_seccomp` (`0x8bf0`)의 대상 번호는 clone(220)/clone3(435)이며
해당 함수의 반환 action도 TRAP이다. 이름의 `trace`를 `SECCOMP_RET_TRACE`로 오인하면 안 된다.
`raw_prctl_set_seccomp`와 `proroot_sigsys_dispatch`도 동일 ELF 안에 있다.

이 근거는 **직접 패치와 신호 기반 중개를 함께 가진 바이너리**라는 구조 해석을
뒷받침한다. `PROROOT_STUB_*` 옵션 문자열의 존재만으로 각 필터의 기본 활성 여부,
프로그램별 적용률, Android 상속 필터와의 동작을 확정하지 않는다.

### D. 신호 처리는 투명성을 따로 검증해야 한다

runtime은 `sigaction`, `sigprocmask`를 export하고 사용자 SIGSYS action을
저장·로드하는 코드를 갖는다. `proroot_sigtrap_handler` (`0x2d28c`, size 180)는
ucontext의 명령어를 검사하고 특정 BRK 형태에서 PC를 다음 명령으로 옮기며,
다른 분기에서는 LR을 사용하고 결과 레지스터를 0으로 설정하는 경로가 있다.
SIGTRAP mask 제거·action 차단 관련 진단도 존재한다.

이것은 일반 Linux 신호 의미와의 차이를 조사해야 한다는 근거다. 모든 실행에서
guest handler가 차단된다는 주장이나 실제 애플리케이션 오동작 재현은 아니다.
자체 엔진에는 signal PC·mask·altstack·guest handler·재시작의 독립 계약이 필요하다.

### E. 성공 종료가 작업 수행을 뜻하지 않는 경로

`should_emulate_maintainer_helper_success` (`0x1d480`, size 144)는 basename을
`ldconfig`, `ldconfig.real`, `update-initramfs`와 비교한다. runtime의 execve
`0x1b350–0x1b38c`에는 이 helper의 결과를 검사하고 맞는 분기에서 `_exit(0)`을
호출하는 코드가 있다. 앞선 조건과 다른 실행 경로도 있으므로 해당 명령이 항상
생략된다고 주장하지 않는다. systemctl 관련 별도 emulation 심볼도 관찰되지만
이번 검토에서 그 전체 동작은 분석하지 않았다.

패키지 smoke의 성공 코드만으로 실제 Linux 동작 동등성을 증명할 수 없다는
구체적인 사례다. 우리 엔진은 배포판의 loader/cache·패키지 결과물을 함께 검증하고,
환경에서 의도적으로 금지하는 서비스 동작은 명시적 배포판 정책으로 분리한다.

### F. 작은 배포 파일과 작은 실행 메모리는 다르다

ELF PT_LOAD를 직접 합산한 값이다. `p_memsz - p_filesz`는 파일에 내용이 없는
zero-fill 영역이며 실제로 fault-in된 RSS/PSS가 아니다. 아래 합계에는 페이지
정렬, 추가 mmap, guest 라이브러리, sharing 효과가 반영되지 않는다.

| 파일 | 합계 p_filesz | 합계 p_memsz | zero-fill |
| --- | ---: | ---: | ---: |
| launcher | 35,528 | 73,760 | 38,232 |
| runtime | 275,048 | 1,419,152 | 1,144,104 |
| bridge | 19,344 | 32,832 | 13,488 |
| linker | 75,336 | 9,725,004 | 9,649,668 |
| stub loader | 92,696 | 115,472 | 22,776 |

다섯 파일이 항상 동시에 resident라는 증거도 없다. 이 값으로 proroot가 가볍거나
무겁다고 판정하지 않고, 새 엔진의 메모리 검증에서 mapped/committed/resident와
프로세스별 private/shared를 구분해야 한다는 요구만 도출한다.

## 4. 자체 설계에 반영한 결정

| 관찰 | 구체화한 계약 |
| --- | --- |
| 후킹·패치·TRAP이 공존 | 모든 진입을 공통 syscall ABI와 부작용 상태로 정규화; 경로별 의미 중복 금지 |
| 주변 명령·relay·cache 관리 필요 | PatchPlan 검증/공개/무효화 상태, 원래 PC 매핑, 실패 시 TRAP 또는 명시적 미지원 |
| RWX와 mem 쓰기 경로 존재 | 실행 전 W^X 전환을 기본으로 하고 기존 코드 실행 중 패치를 초기 범위에서 제외 |
| bootstrap과 필터가 결합 | bootstrap gate 주소와 exec handoff를 versioned 내부 계약으로 고정 |
| guest 신호에 별도 개입 | guest signal state와 transport state 분리, PC/SP 수정과 longjmp 포함 검사 |
| helper 성공 emulation | 패키지 결과·부작용 검증, syscall 계층의 명령 이름별 성공 위장 금지 |
| loader와 runtime의 별도 메모리 영역 | artifact 크기와 별도로 페이지·private dirty·PSS 예산 측정 |

상세 상태와 검증 사례는 [실행 계약](USERSPACE_ENGINE_EXECUTION.md)에 정의한다.
proroot의 함수 배치·opcode template·내부 파일 형식·고정 주소를 복제하지 않는다.
proot-rs의 소유권 분리 검토와 Direct Linux의 역사적 참고 범위는 기존 설계대로 유지한다.

## 5. 증거의 한계와 검증 상태

5개 artifact hash/크기 대조가 통과했다. ELF virtual address를 PT_LOAD의 file
offset으로 변환하여 13개 핵심 instruction word를 검사했고, 5개 AArch64 헤더와
5개 memory 합계 검사도 통과했다. 공개 disassembly에 대한 Jev 검토에서 직접 분기
패치와 조건부 성공 종료 주장은 높은 지지를 받았고, protection 7 주장은 낮은
신뢰도(0.38)로 재검토 대상으로 분류됐다. 해당 부분은 syscall 번호·레지스터 설정·
호출 지점의 원본 bytes를 별도로 대조했다. Jev 판단을 실행 증거로 쓰지 않는다.

기본 실행 경로의 선택, thread 안전성, 신호 정확성, 16 KiB 정책 적합성,
모든 프로그램의 syscall 포괄성, 실제 PSS와 wall time은 여전히 동적 검증 대상이다.
이 분석은 proroot를 실행하거나 Android 기기에 설치한 결과가 아니다.
