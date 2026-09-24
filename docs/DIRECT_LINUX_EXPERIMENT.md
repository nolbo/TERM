# Direct Linux: 별도 무특권 Linux 실행 유형

2026-09-24. **개발 중이며 사용자용 Linux 환경은 아직 완성되지 않았다.**
기존 Rootless, 설치 데이터, 기본 런타임을 교체하지 않는다. 이 문서는 새 유형의
설계와 현재 증거를 관리한다. 과거 Rootless의 특정 배속 목표를 이번 요청의
완료 조건으로 대신 사용하지 않는다.

## 요청과 관찰

목표는 특별한 권한 없이 쾌적한 Linux 환경을 제공하는 것이다. 기존 구조에서
벗어날 수 있지만 Android 정책 때문에 실제 사용이 곤란한 설계는 채택하지
않는다. 기존 Rootless를 대체하지 않고 별도 유형으로 개발한다.

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

## 남은 구현과 완료 증거

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
4. **프로세스·신호:** guest sigaction/mask/altstack 가상화, TLS 독립 handler,
   thread/shared-VM spawn/execveat, 전체 vfork 의미, EINTR/cancellation과 robust
   mutex·확장 CPU 상태, guest seccomp가 남았다. 기본 fork/wait와 exec의
   signal/CLOEXEC 검증은 완료했다. guest exec는 APK 런타임과 필터를 유지한다.
5. **Linux 환경:** 별도 fixture에서 shell/Python/로컬 Git을 실행했다.
   독립 설치·복구/package DB에서 shell, Python, Git, apt/dpkg
   설치·업그레이드·삭제, 네트워크/PTY 검증. 기존 rootfs를 공유하거나 패키지
   관리를 도구 몇 개로 축소해 완료를 주장하지 않는다.
6. **제품 통합:** 공통 backend/session, 명시적 선택·지원 상태·오류, 독립 storage
   identity, capability broker, detach/Activity 재생성 뒤 세션 유지, terminate.
7. **체감 개선:** 같은 기기·입력·배포판·저장소의 최신 가속 Rootless와 교대하여
   shell 시작, Python import, Git status/checkout, 오프라인 패키지 작업, 작은
   파일 및 빌드를 측정한다. 중앙값/p95, 전체 시간, CPU/메모리, 다중 세션과
   발열을 포함한다. 순차 I/O/fsync 의미와 호환성을 유지하고 회귀를 숨기지 않는다.

현재 목표는 **미완료**다. 별도 유형의 기반 코드와 선택 근거는 생겼지만 사용자가
선택하여 일반 Linux 작업을 할 수 있는 새 환경은 아직 없다.
