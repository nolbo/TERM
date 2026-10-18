# Direct Linux 실험 — 개발 중단·기록 보존

2026-09-30 사용자 지시에 따라 별도 Direct Linux 런타임의 개발·제품화를 중단하고 [기존 Userspace Linux 개선 설계](ROOTLESS_AGENT_PERFORMANCE.md)의 구현으로 전환했다. 기존 Android Native/Bionic, Userspace Linux, AVF 환경은 유지한다.

**Rootless와의 호환성 동등성 및 C01–C45 전체 목표는 달성되지 않았다.** 아래 설계, 부분 성공, 성능 결과, 남은 한계는 2026-09-24부터의 연구 기록이며 현재 개발 계획이나 새 Rootless 성능 근거가 아니다. 소스·감사·원시 결과는 [중단된 실험 디렉터리](../tools/direct-linux/README.md)에 보존한다.

제품 `NativeExecutableLoader`에서 `direct-linux-probe`와 `direct-linux-loader`를 제거했고, 관련 Android instrumentation은 [보관 디렉터리](../tools/direct-linux/archive/README.md)로 옮겨 활성 빌드에서 제외했다. 실험 빌드·패키징 스크립트도 기본적으로 실행을 거부하며, 명시적인 역사 재현 옵션만 허용한다. Direct Linux는 사용자 runtime 선택에 추가된 적이 없다.

이하의 요청·방향·완료 조건은 중단 전 실험의 기록이다.

## 요청과 관찰

목표는 특별한 권한 없이 쾌적한 Linux 환경을 제공하는 것이다. 기존 구조에서
벗어날 수 있지만 Android 정책 때문에 실제 사용이 곤란한 설계는 채택하지
않는다. 기존 Rootless를 대체하지 않고 별도 유형으로 개발한다.

사용자가 추가한 완료 조건은 **기존 Rootless와 동등하거나 그 이상의 호환성**이다.
현재 Direct Linux는 이 조건을 충족하지 못한다. 아래 단계별 성공이나 일부
벤치마크의 개선을 전체 호환성의 증거로 대신하지 않는다.

## 호환성 동등성 기준

비교 대상은 저장소가 제공하는 PRoot + preload 가속 Rootless다. 같은 기기·정책·
배포판·패키지·입력으로 동작을 비교하고 엔진/라이브러리 hash와 실패를 기록한다.
fixture에 복사한 Ubuntu 도구의 성공은 독립 Debian 설치와 패키지 관리 검증을
대신하지 않는다. 구체적인 비교 범위는 다음과 같다.

- 공식 Debian apt/dpkg의 설치·업그레이드·삭제, 스크립트와 패키지 DB 정합성.
- 기존 Rootless가 지원하는 경로·링크·가상 소유권·권한·FD·proc/device 의미.
- fork/exec/스레드, TLS, 신호·대기·취소, 메모리 매핑과 동시 실행.
- PTY, 터미널 모드·작업 제어·크기 변경, 셸과 일반 대화형 프로그램.
- DNS·소켓·네트워크 도구, Python/Git/빌드 등 실제 개발 작업.
- 동일한 세션 생성·재연결·명시적 종료, UI 분리·재생성 시 실행 유지.
- 일반 enforcing Android 기기의 정책과 기존 지원 범위에서의 실행 가능성.

Rootless에서 통과하는 동작이 새 유형에서 실패하면 해결해야 할 호환성 결손이다.
프로그램 기능을 끄거나 특정 명령을 몰래 기존 런타임으로 재실행해 통과 처리하지
않는다. Rootless 자체의 실패도 그대로 남기며, 그것으로 다른 미구현 기능을
면제하지 않는다. 좁은 회귀 테스트의 통과만으로 위 범위 전체를 보장하지 않는다.
동등성을 막는 구조적 문제가 확인되면 새 유형의 구조를 변경해야 하며, 성능을
이유로 호환성 요구를 낮추지 않는다. 현재 스레드·가상 identity/hardlink·전체
신호·네트워크·설치기·제품 세션 통합과 enforcing 검증은 명백한 미완료 항목이다.

현재 Rootless는 PRoot와 glibc preload 가속을 결합한다. 일반 파일, 프로세스,
신호 처리는 여전히 tracer와 결합되어 있다. 기존 순차 I/O 측정에서는 실제
저장소 비용이 지배적이었고, 추적 제거만으로 모든 작업을 2배로 만들 근거는
없었다. [기존 I/O 검토](ROOTLESS_IO_GOAL_AUDIT.md)와
[현재 가속](ROOTLESS_EMULATED_ACCELERATION.md)은 이 실험과 별개다.

## 선택한 개발 방향

임시 이름은 **Direct Linux**다. Bionic 도구를 쓰는 Android Native와 구별하며,
목표는 ARM64 Linux/glibc 프로그램을 재컴파일하지 않고 실행하는 별도 사용자
공간 런타임이다. CPU 명령 전체를 에뮬레이션하지 않고 Linux 호출의 호환성
처리를 프로세스 내부로 옮긴다. 이 목표가 이미 구현됐다는 뜻은 아니다.

```text
Runtime service / SessionController (PTY와 세션 소유)
  └─ APK에 포함된 Direct Linux 실행기
      ├─ ELF와 동적 링커 로더 / guest exec
      ├─ 프로세스 내부 Linux syscall dispatcher
      │   ├─ 경로, FD, 가상 identity, 링크 의미
      │   └─ 호스트에서 허용된 syscall만 호출
      └─ 독립 rootfs + package DB + home
          └─ 명시적으로 연결한 workspace
```

첫 후보는 seccomp `RET_TRAP`/SIGSYS로 변환할 호출을 받는다. read/write 등
변환이 불필요한 호출은 직접 실행하고, open/stat/exec 등은 내부에서 처리한다.
APK 코드 안의 정확한 host syscall 호출 위치만 자체 필터의 예외로 삼는다.
상속된 Android 필터는 그대로 적용된다. 이 방법은 컨텍스트 전환 비용을
줄이지만 신호 진입·복귀와 실제 파일시스템 비용은 남는다.

[Linux seccomp 계약](https://docs.kernel.org/userspace-api/seccomp_filter.html)은
`no_new_privs`를 통한 무특권 필터 설치와 SIGSYS 결과 레지스터 수정을 허용한다.
더 엄격한 기존 필터를 새 ALLOW 규칙으로 풀 수는 없다. 이 구조를 Android
보안 정책 우회나 새 보안 sandbox로 표현하지 않는다.

| 다른 후보 | 판단 |
| --- | --- |
| PRoot preload 확대 | 기존 유형의 개선에 유용하지만 raw syscall/exec의 tracer 결합이 남음 |
| USER_NOTIF 외부 파일 서버 | 기존 통합 실험에서 전체 작업 회귀 관찰; IPC와 상태 동기화 비용이 남음 |
| 전체 CPU 에뮬레이션 | Linux ABI 포괄성에는 유리하지만 ARM64 CPU 작업의 비용 증가 가능성이 큼 |
| Bionic 전용 패키지 모음 | 기존 Native와 겹침; 일반 Linux 환경이라는 목표를 도구 모음으로 축소하지 않음 |
| namespace/chroot/FUSE/AVF | 일반 앱이 보장받지 못하는 권한/플랫폼 구성은 필수 조건으로 두지 않음 |

## Android 정책 기준

- 중앙 `NativeExecutableLoader`가 승인한 APK native library 경로에서 시작한다.
  root/ADB/Shizuku, 플랫폼 서명, 시스템 앱 설치, target SDK 하향은 제품 사용
  조건이 아니다. ADB는 개발 검증에만 사용한다.
- rootfs는 데이터다. 앱 데이터의 ELF를 커널 `execve`로 직접 실행하는 설계에
  의존하지 않는다. APK 로더가 읽고 매핑하는 방법을 검증한다. 첫 시험은 익명
  RW 메모리에 시험 코드를 쓴 후 RX로 바꾸어 실행하며 RWX를 만들지 않는다.
  실제 ELF, 동적 링커, JIT와 메모리 공유 비용은 별도 검증 대상이다.
- [AOSP 앱 정책](https://android.googlesource.com/platform/system/sepolicy/+/main/private/app_neverallows.te)은
  현대 target의 앱 데이터 `execute_no_trans`와 일반 앱의 하드링크 생성을
  제한한다. 실제 하드링크와 openat2를 필수 조건으로 두지 않는다. 하드링크는
  별도 사용자 공간 저장 의미와 복구를 구현해야 한다.
- [AOSP app.te](https://android.googlesource.com/platform/system/sepolicy/+/main/private/app.te)의
  `execmem` 규칙은 익명 코드 실행 후보의 근거다. 제조사/버전별 실제 기기
  검증을 대신하지 않는다. Bionic에 [process_vm_readv API](https://android.googlesource.com/platform/bionic/+/master/libc/include/sys/uio.h)가
  있다는 사실도 모든 기기의 실제 호출 성공을 보장하지 않는다.
- SELinux, OS seccomp, 권한, capability broker를 완화하지 않는다. 미지원
  기기/호출은 명시적으로 실패하며 명령을 Rootless/Native로 몰래 재실행하지
  않는다. 사용자에게 새 유형을 제공하기 전에 지원 조건을 판정한다.
- 같은 Android UID의 guest를 앱과 강하게 격리하는 경계는 아니다. trampoline과
  내부 FD에 broker 자격 증명이나 host-only 비밀을 두지 않는다.

## 구현된 첫 시험

소스와 재현법은 [tools/direct-linux](../tools/direct-linux/README.md)에 있다.
APK-owned Bionic 실행기가 ptracer 없이 SIGSYS를 받아 `/probe/이름`을 fixture
디렉터리의 상대 경로로 바꾼다. 잘못된 포인터는 자기 프로세스에 대한 page-bounded
`process_vm_readv`로 검사한다. handler는 malloc/stdio/잠금을 사용하지 않는다.
전용 syscall trampoline에서 openat를 호출하고 결과를 ARM64 signal frame에
돌려준다. read/fcntl/close는 직접 호출한다.

실제 Android instrumentation 앱이 `ProcessBuilder`로 시작한다. run-as는 완료된
JSON을 읽을 때만 사용한다. root UID, CapEff가 있는 프로세스, ptracer가 붙은
실행을 거부한다. 기존 rootfs는 사용하지 않고 시험 디렉터리만 생성·삭제한다.

통과한 검사: APK digest/실행 경로, W→X→W→X 코드 실행, SIGSYS 결과,
CLOEXEC/내용/close, ENOENT/ELOOP/EFAULT, 읽기 가능 페이지 끝의 NUL,
접근 불가능한 다음 페이지, 쓰기·경로 이탈 거부, 네 스레드의 signal stack,
fork 뒤 처리, 추가 EACCES 필터가 host trampoline에서도 유지됨.

ARM64 API 35 redroid에서 최신 가속 PRoot 엔진과 7회 교대 비교했다.
고정 파일 5,000회 raw open/read/fcntl/close의 중앙값은 직접 실행 8.797ms,
내부 TRAP 26.436ms, PRoot 261.583ms다. TRAP은 호출마다 한 번 발생했고
자발적 문맥 전환은 0회, PRoot는 10,000회였다.
[원시 결과](../tools/direct-linux/results/accelerated-engine-2026-09-24.json).

**약 9.9배는 고정 경로 시험의 차이다.** raw SVC는 glibc preload 가속을
사용하지 않는다. 일반 rootfs path walker, 가상 ID, ELF/exec, 시작 시간도
비교에 없으므로 최신 Rootless 전체가 9.9배 빨라진다는 증거가 아니다.
기기는 SELinux Disabled, 최초 Seccomp=0이다. enforcing 휴대폰의 정책 적합성은
미검증이다. 이 첫 probe의 Bionic TLS 카운터는 glibc guest에 이식하지 않는다.

## ELF 실행 단계: 2026-09-24

[새 ELF 실행기](../tools/direct-linux/ELF.md)는 실제 ARM64 Linux ET_EXEC/ET_DYN,
PT_INTERP, auxv/stack/BSS/heap을 구성하고 별도 root에서 glibc를 실행한다.
TLS·libc·할당기에 의존하지 않는 dispatcher를 구현했으며 빌드가 외부 함수
참조를 검사한다. glibc가 TLS를 초기화한 뒤에도 파일 호출이 동작한다.

Ubuntu의 수정하지 않은 GNU true/printf와 dash의 내부 명령·입력 리디렉션·chdir,
정적/동적 glibc 회귀 프로그램을 Android 앱 프로세스에서 실행했다. ELF 손상
12종과 비 ELF 파일 거부, 실제 SIGBUS, signal mask 보류·해제도 검증했다.
초기 주 ELF/링커는 익명 메모리에 적재하지만 guest 공유 라이브러리의 mmap은
파일 매핑·EOF/SIGBUS 의미를 유지한다. mmap 정책의 거부는 그대로 전달한다.

같은 glibc 프로그램·파일로 최신 Rootless preload 가속을 실제 사용한 7쌍
비교에서 stat 39.415→27.081ms(1.46배), open/read/close 59.004→45.343ms
(1.30배)였다. 각 5,000회이고 Rootless 가속 counter도 매번 5,000회 증가했다.
[원시 결과](../tools/direct-linux/results/elf-runtime-2026-09-24.json).
이는 단일 warm 파일 작업의 결과이며 전체 Linux 성능 개선의 증거는 아니다.
이 초기 ELF 단계에는 fork/exec가 없었다. 아래 프로세스 단계에서 기본 경로를
추가했으며 fake-root/hardlink, 패키지·세션 통합은 아직 남았다.

## 프로세스 실행 단계: 2026-09-24

[프로세스 구현·검증](../tools/direct-linux/PROCESS.md)은 실제 dash의 외부 명령,
파이프, command substitution, background wait와 shebang script를 실행한다.
libc/TLS 독립 exec 준비·교체와 guest 매핑 수명을 구현했다. 실패한 준비는 기존
이미지/FD를 보존하며 성공한 exec는 PID·cwd·환경·CLOEXEC·signal 의미를 처리한다.
vfork 호환 경로는 copy-on-write와 부모 대기 barrier를 사용하며 공유 VM을
구현했다고 주장하지 않는다. pthread/shared-VM spawn과 전체 exec 의미는 남았다.

56개 상위 실행과 내부 자식·반복 exec 검사, 42개 시간 표본이 통과했다.
[원시 결과](../tools/direct-linux/results/process-runtime-2026-09-24.json).
최신 가속 Rootless 대비 파일 조회·읽기는 1.47배·1.32배 빨랐지만 셸+외부 명령
30회는 **84.300ms 대 49.595ms로 1.70배 느렸다**. Android waitFor 관찰 시간은
측정 해상도 때문에 제외하고 guest CLOCK_MONOTONIC으로 실제 작업을 측정했다.
프로세스 준비·교체 비용의 원인은 아직 프로파일링하지 않았으며 다음 최적화
대상이다. 전체 성능 목표는 달성하지 못했다. enforcing 기기 검증도 여전히 없다.

## exec 비용 개선: 2026-09-24

[단계별 프로파일과 최적화](../tools/direct-linux/EXEC_PERFORMANCE.md)에서 새 익명
실행 페이지의 캐시 처리·권한 전환이 큰 비용임을 확인했다. 검증 스냅샷·동일 FD를
유지한 채 주 ELF와 링커를 MAP_PRIVATE로 매핑하고 내용을 비교한다. BSS 부분
페이지 끝까지 0으로 초기화하고, 실행 뒤 원본 파일 hash와 임시 FD 정리도 검증한다.
매핑 거부는 익명 복사로 우회하지 않는다. 진입 뒤 파일은 동결되지 않으며 동시
변경·축소는 파일 매핑의 의미를 따른다. 프로세스 간 완전한 ETXTBSY 의미는 남았다.

최종 59개 상위 실행·42개 표본에서 외부 명령 30회는 **51.995ms**로 이전 실험의
84.300ms보다 약 38% 줄었다. 같은 실행의 Rootless **47.911ms**보다는 약 9% 느리다.
파일 조회·읽기는 각각 1.48배·1.34배 빨랐다. 셸 결과는 아직 기준보다 느리며,
전체 Linux 작업과 enforcing 기기 적합성 검증을 대신하지 않는다.
[최종 원시 결과](../tools/direct-linux/results/exec-performance-2026-09-24.json).

## 파일 작업 확장: 2026-09-24

[파일시스템 단계](../tools/direct-linux/FILESYSTEM.md)는 이름이 바뀌거나 삭제된
디렉터리에서도 실제 FD로 상대 경로를 찾고, 생성·삭제·이동·symlink·권한·시간
변경을 처리한다. 같은 UID의 외부 프로세스에 대한 보안 sandbox는 아니며,
Android의 실제 권한 오류를 유지한다. fake identity/hardlink는 아직 없다.

정적·동적 glibc의 동시 rename/열린 FD/삭제된 cwd 회귀 시험과 GNU 파일 도구를
검증했다. 128개 파일 생성·복사·이동·내용 확인·삭제는 7쌍 중앙값에서
**51.328ms 대 95.214ms**로 최신 가속 Rootless보다 **1.85배** 빨랐다.
외부 명령 30회는 **53.458ms 대 48.438ms**로 여전히 약 10% 느렸다.
[원시 결과](../tools/direct-linux/results/filesystem-2026-09-24.json)는 77개 상위
실행·56개 시간 표본을 담는다. stock enforcing 검증과 전체 Linux 환경 완성은
별도 완료 조건이다.

## Python·Git 실행 확장: 2026-09-24

[실제 애플리케이션 단계](../tools/direct-linux/APPLICATIONS.md)는 xattr와 고정된
4개 기본 문자 장치를 추가했다. 수정하지 않은 Python 3.12의 압축·SQLite·아카이브·
메타데이터 복사와 Git의 128개 파일 커밋·상태·브랜치 전환·객체 검사 및 Python의
전체 임시 저장소 삭제가 Direct Linux에서 통과했다. 임의 Android 장치나 전체
`/dev`를 노출하지 않으며 설치기·PTY·사용자용 세션은 아직 없다.

기준 Rootless는 같은 작업의 마지막 삭제에서 ENOTEMPTY가 두 번 재현됐고
`.l2s.*` 메타데이터가 남았다. [실패 기록](../tools/direct-linux/results/applications-reference-failure-2026-09-24.json)을
보존했으며 완전한 애플리케이션 배속은 주장하지 않는다. 기준의 실패 전 Git
구간 약 0.99–1.00초에 비해 Direct의 7회 중앙값은 1.38초여서 최적화가 필요하다.
기존 파일 트리 작업은 같은 최종 실행에서 약 1.84배 빨랐고 외부 명령 반복은
약 12% 느렸다. [최종 보고서](../tools/direct-linux/results/applications-2026-09-24.json)는
애플리케이션 기준 비교를 명시적으로 제외했다고 표시한다.

## Python 자식 프로세스 정리 비용 개선: 2026-09-24

[FD 정리 프로파일과 검증](../tools/direct-linux/SUBPROCESS_PERFORMANCE.md)은 Python의
자식 실행에서 `close_range` 미지원과 guest proc FD 목록 부재가 반복 close를
유발하는 경로를 확인했다. 내부 FD를 보존하며 실제 열린 FD만 순회하는 호환
처리를 추가하고, exec의 CLOEXEC 정리와 공유했다. FD table을 공유하지 않는
현재 clone 범위에서만 UNSHARE가 추가 작업 없이 성립한다. 스레드 지원 시 이
전제를 다시 검토해야 하며, FD가 가득 차면 scanner를 열지 못하는 한계가 남는다.

기존 Direct 측정 대비 7회 중앙값은 Git **1,381.658→454.317ms**, Python 시작부터
전체 작업과 정리까지 **1,518.684→586.068ms**로 줄었다. 전후 측정은 순차 실행이며
교대 비교는 아니다. 같은 최종 실행의 Rootless 대비 파일 트리 작업은 약 1.83배
빠르고 외부 명령 반복은 약 11% 느렸다. Rootless 전체 애플리케이션 비교는 앞서
확인한 삭제 실패로 계속 명시적으로 제외했다. 정적·동적 glibc의 범위·CLOEXEC·
fork·vfork·내부 FD 보존 검사를 포함해 87개 상위 실행과 77개 시간 표본이 통과했다.
[원시 결과](../tools/direct-linux/results/close-range-2026-09-24.json). enforcing 기기,
패키지 설치와 사용자용 세션은 아직 미검증·미구현이다.

## PTY·작업 제어 비교: 2026-09-24

[PTY 단계](../tools/direct-linux/PTY.md)는 기존 `PtyProcess`로 실제 Linux 셸을
시작한다. 고정 장치 보기에 `/dev/tty`를 추가하고, 변환 없는 ioctl을 커널로
직접 보내 호출자의 신호 마스크와 SIGTTOU 의미를 보존한다. 같은 dash·GNU stty·
정적/동적 glibc 검사에서 Direct와 기존 가속 Rootless 모두 제어 터미널·foreground
group·setsid·SIGTTOU·한글 입력·SIGWINCH·Ctrl-C·Ctrl-Z·jobs/bg·명시적 종료가 통과했다.

master 종료 시 Direct guest는 세션 리더로 SIGHUP 종료하며, Rootless guest는
별도 추적기의 자식으로 POLLHUP·EOF·제어 터미널 상실을 확인했다. 초기 테스트의
동일 종료 코드 가정은 관찰된 세션 역할과 Linux 커널 계약에 맞게 수정했다.
40개 일반 실행과 두 엔진의 PTY 시나리오를 포함한
[결과](../tools/direct-linux/results/pty-runtime-2026-09-24.json)를 기록했다. UI 수명주기,
devpts/ttyname/중첩 PTY와 enforcing 기기는 아직 검증하지 않았다. 이 좁은
비교를 기존 Rootless 전체와의 호환성 동등성으로 확대 해석하지 않는다.

## 네트워크·DNS 비교: 2026-09-24

[네트워크 단계](../tools/direct-linux/NETWORK.md)는 IPv4/IPv6 소켓, 익명 socketpair와
파일 FD 전달, epoll을 추가했다. 주소가 있는 호출은 안전하게 복사·검사하며,
블로킹 송신 중에는 guest 신호 마스크를 적용한다. 이때 정리가 필요한 내부
잠금·임시 FD를 보유하지 않아 신호 처리기의 재진입과 siglongjmp를 허용한다.
이름 있는 Unix 소켓의 경로·FD 수명 처리는 미완료이며 호스트 경로로 넘기지 않는다.

Direct와 기존 가속 Rootless 모두 정적·동적 glibc의 TCP/UDP, half-close, 메시지
절단·peek, SCM_RIGHTS/CLOEXEC, EINTR·신호 처리기 재진입·비지역 복귀를 통과했다.
수정하지 않은 Python 표준 라이브러리의 HTTP/TLS 전송, 인증서·호스트 이름 검증,
hosts 파일 조회와 selector도 통과했다. 별도의 정적·동적 glibc resolver는 일반
loopback 포트의 시험 서버에 실제 DNS 질의를 보낸다. A/AAAA, NXDOMAIN, 검색
도메인, 잘못된 질의 ID 거부와 UDP 절단 후 TCP 재시도까지 두 엔진에서 확인했다.
이 시험은 외부 DNS, Android DNS 자동 설정이나 getaddrinfo의 DNS/NSS 통합 검증은 아니다.

기존 ELF·파일·프로세스·Direct Python/Git 및 두 엔진의 PTY 검사와 함께 Android
instrumentation이 8.118초에 통과했다. [원시 결과](../tools/direct-linux/results/network-runtime-2026-09-24.json)는
712개 fixture hash, 46개 일반 실행, 두 PTY 시나리오와 네트워크 결과를 기록한다.
성능 표본은 없으며 이전 Rootless Git 정리 실패도 재검증하지 않았다. 실제 앱 UID와
기존 일반 INTERNET 권한으로 실행했지만 SELinux Disabled redroid이므로 일반
enforcing 기기 검증을 대신하지 않는다. 전체 호환성 동등성은 아직 달성하지 못했다.

## 명령 실행 성능 개선: 2026-09-24

[추가 프로파일과 개선](../tools/direct-linux/COMMAND_PERFORMANCE.md)에서 매 exec마다
실행 파일·인터프리터 전체를 임시 메모리에 읽고 매핑 후 다시 비교하는 중복을
확인했다. 필요한 ELF 헤더·프로그램 헤더·인터프리터 문자열만 보존·재검증하고
코드·데이터는 일반 파일 매핑으로 필요할 때 읽는다. 파일·매핑 범위, 권한,
실행 진입점, 중첩 세그먼트와 RWX 거부 검사는 유지했다. 전체 바이트 스냅샷을
비교하는 의미는 제거했으며, ETXTBSY·파일 불변성과 보안 격리를 보장하지 않는다.

같은 714개 fixture와 기존 가속 Rootless를 사용한 7쌍 측정에서 외부 명령 30회는
Direct **52.351→46.753ms**, 이전 APK로 되돌린 뒤 반복한 측정에서는
**52.326→47.195ms**였다. 각각 10.7%, 9.8% 감소했고 개선 버전과 짝지은 Rootless는
48.819ms, 48.243ms였다. 작은 차이를 전체 Linux 성능 우위로 확대하지 않는다.
Python/Git 전체 작업에는 일관된 추가 개선이 없었다.
[원시 표본](../tools/direct-linux/results/command-performance-2026-09-24.json)에
효과가 뚜렷하지 않아 제거한 MAP_POPULATE 실험도 포함했다.

ELF 메타데이터 변조·페이지 경계를 넘는 헤더·원본 inode 고정의 호스트 회귀와
Android 네트워크·PTY 포함 검사가 통과했다. 후자는 56개 일반 실행과 두 PTY
시나리오이며 [결과](../tools/direct-linux/results/command-runtime-2026-09-24.json)에
기존 Unix 호환성 차이도 기록한다. 표준 Gradle 검증은 성공했고 변경 없는 단위
테스트 결과는 UP-TO-DATE로 재사용했다. stock enforcing 기기 검증은 여전히 없다.

### 인자가 많은 명령의 추가 개선: 2026-09-24

[인자 복사 비용 분석](../tools/direct-linux/ARGUMENT_PERFORMANCE.md)에서 argv/envp의
같은 페이지를 매 항목마다 시스템 호출로 다시 읽는 비용을 확인했다. 포인터 표와
문자열에 각각 한 페이지의 임시 스냅샷을 사용하고, 벡터 또는 exec가 바뀌면 새로
읽도록 했다. 주소 오류·길이 제한과 이미지 교체 전 실패 동작을 유지했다.

96개 인자를 전달하는 외부 명령 30회의 7쌍 비교는 **54.514→49.449ms**, APK를
되돌린 재비교는 **54.123→47.586ms**로 9.3–12.1% 감소했다. 각각 변경 후 Rootless는
49.490ms와 47.399ms였다. 짧은 명령이나 Python/Git 전체 작업의 뚜렷한 추가 개선은
입증하지 않았다. [전체 표본](../tools/direct-linux/results/argument-performance-2026-09-24.json)은
효과가 없었던 고정 크기 버퍼 실험도 기록한다.

호스트 페이지 경계·잘못된 포인터·길이 제한 검사, 네이티브 빌드 감사, 표준 Gradle
검증이 통과했다. 변경 없는 Gradle 단위 검사는 UP-TO-DATE 결과를 재사용했다.
최종 Android 검사는 64개 실행과 두 PTY 시나리오를 통과했다. 기존 스레드 작업과
Rootless는 변경 범위에서 제외했으며, stock enforcing 정책 검증도 아직 없다.

### 짧은 명령의 추가 개선: 2026-09-25

[짧은 명령 실행 비용](../tools/direct-linux/SHORT_COMMAND_PERFORMANCE.md)을 줄이기 위해
그대로 전달하던 syscall의 SIGSYS 왕복, 매 exec의 전체 신호 action 조회, 경로
중간 디렉터리의 중복 fstat을 줄였다. 실제 핸들러 초기화·IGN 보존·루트 상대 링크
처리는 유지하고 호스트 및 Android 회귀 검사로 확인했다.

같은 테스트 APK에 이전/개선 로더를 함께 넣고 Rootless를 포함한 실행 순서를
균등하게 바꿔 측정했다. 18쌍씩 세 차수, 총 **54쌍의 시간 감소율 중앙값은 2.3%**,
54쌍 중 48쌍에서 개선 로더가 빨랐다. 마지막 차수의 짧은 명령 30회 중앙값은
이전 48.613ms, 개선 47.152ms, Rootless 48.859ms였다. 초기 APK 교체 측정의
1.1–6.0% 변동보다 이 교차 측정을 주 근거로 사용한다. 일반 앱·cold cache·물리
기기 성능으로 확대하지 않는다.

표준 Gradle 및 네이티브 검증이 통과했고 변경 없는 단위 검사는 UP-TO-DATE로
재사용했다. 최종 Android 검사도 118개 실행과 두 PTY 시나리오를 통과했다.
[원시 표본](../tools/direct-linux/results/short-command-performance-2026-09-25.json)과
[회귀 결과](../tools/direct-linux/results/short-command-runtime-2026-09-25.json)를 보존한다.
기존 스레드 작업은 이번 로더에서 제외했으며 stock enforcing 정책 검증은 여전히 없다.

### exec 밖의 생성·종료 비용: 2026-09-25

[시작 비용 계측](../tools/direct-linux/STARTUP_PERFORMANCE.md)을 추가해 fork 복귀,
exec 처리, main 진입, 종료와 reap을 같은 시계로 구분했다. 초기 APK의 Bionic·링커·
할당자 매핑이 게스트 자식에게 상속되면서 생성·정리 비용을 만들고 있었다.
게스트 준비 전 해당 매핑에 MADV_DONTFORK를 적용하고 런타임 LOAD/BSS, 호스트 TLS,
vDSO/vvar를 보존했다. 나중에 만드는 게스트·신호 스택은 원래대로 상속한다.
초기 핸들러와 robust-list/clear-child-TID 포인터도 정리한다. 커널의 rseq 참조를
고려해 호스트 TLS는 유지하며, 실제 자식·손자 검사로 상속과 부모 분리를 검증했다.

같은 APK에서 이전 단계 `6bf6203` 대비 **54쌍의 시간 감소율 중앙값은 24.3%**였고
54쌍 모두 빨라졌다. 마지막 차수의 셸+명령 30회 중앙값은 46.825→35.049ms,
동일 Rootless는 48.426ms였다. 진단의 fork/exit 전체는 평균 602→327µs였지만
보고서 출력 비용을 포함하므로 벤치마크와 구분한다. 초기 APK 준비의 일회성
maps 조회 비용은 이 반복 명령 시간에서 제외되며 전체 앱의 추가 가속을 뜻하지 않는다.

네이티브/호스트/표준 Gradle 검증과 Android 신호·네트워크·PTY 회귀가 통과했다.
[원시 표본](../tools/direct-linux/results/startup-performance-2026-09-25.json)과
[회귀 결과](../tools/direct-linux/results/startup-runtime-2026-09-25.json)를 보존한다.
이후 Bionic 호출이나 새로운 커널 등록 포인터를 도입하면 상속 정책을 재검토해야 한다.
기존 스레드 작업은 보존했으며, stock enforcing 기기와 제품 통합 검증은 남아 있다.

## 신호 마스크 호환성: 2026-09-24

[신호 마스크 단계](../tools/direct-linux/SIGNAL_MASKS.md)는 일반 프로그램의
전체 신호 마스크가 내부 SIGSYS 전달까지 막는 충돌을 고쳤다. 같은 초기
테스트에서 Rootless는 통과했지만 이전 Direct는 종료 코드 159로 죽었다.
신호 action과 임시 대기 마스크를 논리 상태와 실제 커널 상태로 나누고,
중첩 대기·siglongjmp로 버린 상태를 안전하게 정리한다. 신호 처리기가
네트워크 대기에 들어갔을 때 바깥 대기의 마스크를 잘못 쓰던 중간 회귀도
재현하여 수정했다. 변경 없는 기존 Rootless와 정적·동적 glibc로 비교했다.

[최종 결과](../tools/direct-linux/results/signals-runtime-2026-09-24.json)는
716개 fixture hash, 113개 일반 실행, 두 PTY 시나리오, 77개 시간 표본을 담는다.
신호·파일·exec·Direct Python/Git·네트워크·PTY 검사가 통과했고, 명령 30회
중앙값은 Direct 48.958ms, Rootless 50.162ms였다. 이는 신호 수정의 추가 성능
향상을 입증하는 비교가 아니며 전체 호환성 동등성의 증거도 아니다.
스레드별 상태, 사용자 altstack, 외부 SIGSYS와 전체 context 가상화는 아직
미완료다. 일반 enforcing Android 기기 검증도 남아 있다.

## 남은 구현과 완료 증거

2026-09-25 [호환성 감사](../tools/direct-linux/COMPATIBILITY_AUDIT.md)는 아래 과제를
45개 개선·검증 항목으로 구체화하고, NDK ARM64 syscall 318개 전체 대조 및 새로
재현한 매핑 소유권/FD 고갈 정리 결함을 기록한다. 커밋된 런타임과 미완료 스레드
변경을 구분했으며, 이번 감사는 런타임 수정이나 호환성 동등성 완료를 뜻하지 않는다.

이후 [호환성 구현 진행 상태](../tools/direct-linux/COMPATIBILITY_PROGRESS.md)에
초기 pthread 실행, DONTFORK 소유권·FD 고갈 정리·leader 종료 뒤 worker 동작의
수정과 Android 검증을 기록했다. 이후 [병행 exec](../tools/direct-linux/CONCURRENT_EXEC.md)는
freestanding APK 런타임에 kernel 재진입하여 다중 thread/nonleader exec를 지원한다.
런타임 자체는 고정 주소이고 guest ASLR과 RELRO는 유지한다.
[다중 thread fork/FD-table 소유권](../tools/direct-linux/FORK_FD_TABLES.md)도 구현·검증했다.
[FD 기반 exec](../tools/direct-linux/EXEC_FD.md)는 execveat/fexecve와 unlink된 파일,
스크립트의 FD 경로·CLOEXEC 오류를 native Linux와 대조하고 Android에서 검증했다.
[신호 상태 소유권](../tools/direct-linux/SIGNAL_OWNERSHIP.md)은 공유 VM과 독립된
signal disposition 객체를 도입하고 실제 kernel clone 구성요소를 검증했다.
[exec 준비 메모리 소유권](../tools/direct-linux/SCRATCH_OWNERSHIP.md)은 준비 매핑을
task별로 추적하고 guest 스택으로의 이전 및 exit/exec 후 회수를 검증했다.
[task identity와 이탈 관찰](../tools/direct-linux/TASK_OWNERSHIP.md)은 TGID·exec 선택과
관찰된 VM 이탈 뒤 회수를 연결했다. nonleader exec의 TID 교환을 mm 교체로 오인하지
않으며, 관찰 불가능한 이탈과 변경 도중 사망 복구는 미완료다.
[실제 spawn·인자·timer·파일 노드](../tools/direct-linux/SPAWN_COMPATIBILITY.md)는 kernel shared-VM vfork와 정적·동적 glibc posix_spawn, Linux의 RLIMIT_STACK 연동 인자 한도, timer/queued signal, 실제 hardlink/FIFO를 구현한다. vfork 자식의 준비 메모리는 부모가 예약하고 kernel 완료 뒤 회수한다. clone3·일반 CLONE_VM, 예약된 SIGSYS, enforcing 기기의 hardlink 정책 등은 남아 있다.
추가 FD/FS 조합을 비롯한
전체 45개 목표는 그 문서의 항목별 상태로 계속 추적한다.

이후 [Unix pathname 단계](../tools/direct-linux/UNIX_SOCKETS.md)에서 지속되는 root FD
별칭으로 경로와 반환 주소를 변환했다. 임시 FD는 블로킹 호출 전에 모두 닫으므로
신호 처리기의 chdir와 siglongjmp 후에도 남지 않는다. 정적·동적 stream 검사는
두 엔진 모두 통과했다. Direct의 named datagram 검사는 통과했지만 Rootless의
sendto는 ENOENT로 실패했다. 반대로 100바이트 이름은 Rootless에서 성공하고
Direct에서 ENAMETOOLONG으로 실패한다. [비교 결과](../tools/direct-linux/results/unix-runtime-2026-09-24.json)에
이 차이를 명시했으며, 긴 이름 별칭·abstract·이름 있는 배치 수신·동시 경로 의미는
미완료다. 일부 pathname 지원을 전체 Unix 소켓 호환성으로 간주하지 않는다.

1. **정책:** fixture/redroid 검증은 완료. stock enforcing 기기에서 같은 APK의
   seccomp/메모리/포인터 복사를 재검증해야 한다.
2. **ELF 수직 실행:** 초기 loader/auxv/stack/TLS/PT_INTERP와 실제 glibc 프로그램
   실행·손상 입력 검증과 기본 exec 교체/메모리 수명 검사 완료. 더 넓은 ELF·JIT,
   stock 기기 매핑 정책 검증이 남았다.
3. **파일 의미:** 기본 경로·symlink·dirfd·cwd·동시 rename 및 생성/삭제/메타데이터,
   실제 UID/권한/errno, 열린 FD의 unlink 의미를 검증했다. fake identity와
   에뮬레이션 hardlink, 전체 ACL 의미, proc/devpts와 전체 동시성 의미가 남았다.
   기본 xattr 및 null/zero/random/urandom의 고정 장치 보기는 검증했다.
   symlink 시간 변경은 Linux 5.8+이며 오래된 커널의 오류를 숨기지 않는다.
4. **프로세스·신호:** 사용자 altstack·외부 SIGSYS·전체 신호 context 가상화,
   전체 thread/FD/FS 조합/shared-VM spawn, 전체 vfork 의미와
   확장 CPU 상태, guest seccomp가 남았다. 기본 pthread/robust owner death와
   [blocking EINTR/cancellation](../tools/direct-linux/BLOCKING_SIGNALS.md)을 검증했다. 기본 fork/wait와 exec의
   signal/CLOEXEC와 일반 action/대기 마스크·중첩 nonlocal 복귀 검증은 완료했다.
   한 스레드 exec는 APK 런타임을 유지하며 병행 exec는 같은 APK ELF를 kernel
   재실행한다. 두 경로 모두 필터를 유지하고 guest app-data ELF는 kernel 실행하지 않는다.
5. **Linux 환경:** 별도 fixture에서 shell/Python/로컬 Git을 실행했다.
   독립 설치·복구/package DB에서 shell, Python, Git, apt/dpkg
   설치·업그레이드·삭제, 전체 Unix 소켓 의미·추가 소켓 family·DNS/NSS 통합·외부
   DNS/인터넷·배포판 CA 설정과 devpts/중첩 PTY/대화형 앱 검증. loopback HTTP/TLS와
   로컬 glibc DNS 전송 검사를 전체 네트워크 지원으로 간주하지 않는다. 기존 rootfs를 공유하거나 패키지
   관리를 도구 몇 개로 축소해 완료를 주장하지 않는다.
6. **제품 통합:** 공통 backend/session, 명시적 선택·지원 상태·오류, 독립 storage
   identity, capability broker, detach/Activity 재생성 뒤 세션 유지, terminate.
7. **체감 개선:** 같은 기기·입력·배포판·저장소의 최신 가속 Rootless와 교대하여
   shell 시작, Python import, Git status/checkout, 오프라인 패키지 작업, 작은
   파일 및 빌드를 측정한다. 중앙값/p95, 전체 시간, CPU/메모리, 다중 세션과
   발열을 포함한다. 순차 I/O/fsync 의미와 호환성을 유지하고 회귀를 숨기지 않는다.

현재 목표는 **미완료**다. 별도 유형의 기반 코드와 선택 근거는 생겼지만 사용자가
선택하여 일반 Linux 작업을 할 수 있는 새 환경은 아직 없다.
