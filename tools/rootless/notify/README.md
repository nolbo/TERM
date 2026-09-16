# Rootless 파일 디스크립터 전달 실험

이 프로그램은 PRoot에 통합되지 않은 ARM64 전송 시험이다. 부모가 직접 만든
파일 하나만 제공한다. 일반 경로 변환, binding, guest 권한 및 FD 상태를
구현하지 않으므로 측정값을 전체 rootless 성능으로 해석하면 안 된다.

## 빌드와 실행

```sh
bash tools/rootless/notify/build.sh /tmp/terminal-notify
```

기존 ARM64 Clang과 NDK r28 sysroot로 정적 glibc와 Android/Bionic 실행 파일을
만든다. CLI는 `PROGRAM BASE_DIRECTORY direct|notify COUNT`이며 COUNT는
1~100000이다. notify는 root UID 실행을 거부한다. 프로그램이 만든 임시
디렉터리와 파일만 사용한다.

별도 성능 APK에 정적 시험 파일을 포함하려면 기존 APK 생성 명령에
`PROOT_TEST_NOTIFY_BINARY=/tmp/terminal-notify/notify-open-test`를 지정한다.
일반 제품 APK에는 시험 파일을 추가하지 않는다.

앱 프로세스 검증은 별도 instrumentation APK에 Bionic 파일을
`lib/arm64-v8a/librootless-notify-probe.so`로 패키징하고 다시 서명한 뒤 실행한다.
`RootlessNotifyTransportTest`에 `rootlessNotifyTransport=true`와
`rootlessNotifySha256`을 전달해야 한다. 중앙 실행 로더의 고정 허용 이름을
사용하며 APK 밖 symlink와 누락된 파일은 거부한다. 일반 테스트 실행은 건너뛴다.

## 검증 범위

자식의 no_new_privs 설정, USER_NOTIF listener 생성, SCM_RIGHTS 전달,
process_vm_readv, 요청 ID 검증, ADDFD_SEND, 읽기 전용 FD, CLOEXEC 유무,
ENOENT/EFAULT, 완료된 요청 ID, 자식 종료로 취소된 요청 ID를 검사한다.
요청/응답 메모리는 커널이 보고한 크기로 할당한다. 읽은 guest 문자열을
호스트 경로로 쓰지 않고 고정 시험 이름만 인정한다.

API 의미는 [커널 seccomp 문서](https://docs.kernel.org/userspace-api/seccomp_filter.html)를
기준으로 한다. 향후 PRoot 통합에서는 USER_NOTIF와 TRACE의 우선순위,
경로·dirfd·umask·FD 상태, syscall 부작용과 요청 취소, ptrace 이벤트 대기를
함께 처리해야 한다. 이 시험의 고정 파일 분기로 대체할 수 없다.

## 2026-09-15 측정

[원시 결과](results/open-transport-2026-09-15.json): 같은 앱 전용 ext4에서
캐시가 데워진 읽기 전용 open/close 10000회, 네 경로를 교대로 7회 측정했다.

| 경로 | 중앙값(ms) |
| --- | ---: |
| Native | 16.986 |
| 고정 파일 notification | 141.996 |
| 현재 실험 PRoot | 468.299 |
| 기존 제품 PRoot | 517.189 |

고정 파일 전달은 현재 PRoot의 약 3.30배다. 이미 열린 부모 dirfd를 사용하고
일반 경로 해석을 생략하므로 전체 파일 작업에 같은 배속을 예측하지 않는다.
7회 모두 전송 검사를 통과했다.

- 정적 시험 SHA-256: `1d504d2c1e395613fc5a7ff0034f035a6ba6da0eeed87c647bf8b14d81bc2499`
- Bionic 시험 SHA-256: `e806cfce7cfbaed345cd296d9b89fff1f18bc6f41b6e30e1462495f468d67922`
- PRoot SHA-256: `e7f7f3e6cbebb90d121a7c0ad4ad0b4e10a655e30e30e47d0239602ac7ea5a9c`

Android API 35/ARM64 redroid, 커널 6.17.0-1020-oracle에서 앱 UID와
CapEff=0으로 통과했다. 중앙 로더와 ProcessBuilder를 거치는 Bionic
instrumentation도 `OK (1 test)`, 0.117초였다. 이 환경은 앱 프로세스도
초기 Seccomp=0이므로 stock Android의 seccomp/SELinux 정책 검증은 남아 있다.

호스트 샌드박스 실행은 process_vm_readv의 EPERM으로 실패했다. 실패 시
시험 파일 정리는 확인했으며 호스트 기능 시험 성공으로 기록하지 않는다.
C 두 빌드의 경고 오류 처리, 셸 문법 검사, 표준 Gradle 검증 및
`:android-runtime:assembleDebugAndroidTest`는 통과했다(170 tasks, 14 executed).
이번 단계는 새 PRoot 구현이 아니므로 Debian 설치를 재실행하지 않았다.

## 2026-09-16: PRoot 통합 실험 — 기본 채택 보류

`open-notify.c`와 `proot-notify.patch`는 위 고정 파일 시험과 별개로 PRoot의
실제 경로 변환과 이벤트 루프에 FD 전달을 연결한다. 빌드는 다음과 같다.

```sh
PROOT_BUILD_FASTSTAT=1 PROOT_BUILD_NOTIFY=1 \
  bash tools/rootless/build-proot-arm64.sh /tmp/terminal-notify/payload
```

실행 시 tracer 환경에 `PROOT_OPEN_NOTIFY=1`을 명시해야 한다. 옵션이 없으면
기존 waitpid 경로이며, 제품 payload는 바뀌지 않는다. 기존 stat/path/rename
옵션도 각각 명시한다. seccomp 끔 및 fake_id0/mountinfo 외 확장은 거부한다.
기능이 없는 커널에서 묵시적으로 성공했다고 표시하지 않고 시작에 실패한다.

native ARM64의 읽기 전용 openat만 notification으로 처리한다. 생성·쓰기,
O_PATH, O_NOFOLLOW, 알려지지 않은 flags 및 다른 ABI는 기존 TRACE 경로다.
단순한 절대/cwd 경로는 binding 선택 후 openat2의 NO_SYMLINKS로 검증하고
O_PATH로 inode를 고정한다. 일반 파일·디렉터리인지 확인한 뒤 같은 inode를
읽기용으로 열어 전달한다. 접근 실패·symlink·중첩 binding·pseudo 경로·dirfd
경로는 PRoot의 전체 변환을 사용한다. guest 포인터는 페이지 경계마다 복사하며
notification으로 막힌 프로세스에 ptrace 메모리 읽기를 시도하지 않는다.

부모는 SIGCHLD를 차단한 waitpid(WNOHANG) 검사와 원자적으로 차단을 푸는
ppoll을 사용한다. pipe shadow의 주기적 회수와 취소된 notification의 RECV
깨우기를 유지한다. 마지막 자식 종료 후 listener HUP를 오류로 취급하던
초기 경쟁 조건을 고쳤으며 최종 시험에서는 정상 종료 코드까지 확인했다.

**아직 일반 목적 backend로 사용할 수 없다.** notification 대상 FIFO·장치
등 특수 파일은 EOPNOTSUPP이며, /proc/self/auxv의 기존 종료 후 보정 등
특수 open bookkeeping은 아직 통합하지 않았다. 기존 guest ptracer와의
상호 작용, stock Android 정책, 새 배포판 설치도 이 통합판에서 검증하지 않았다.
일반 파일 성공 시험으로 이 제약이 해결됐다고 간주하지 않는다.

[7회 교대 비교](results/integrated-open-2026-09-16.json)의 중앙값:

| 작업 | 이전 실험 PRoot(ms) | notification 통합(ms) | 배속 |
| --- | ---: | ---: | ---: |
| 작은 파일 읽기 | 158.827 | 167.190 | 0.95 |
| Python import | 173.323 | 179.083 | 0.97 |
| tar 추출 | 732.823 | 785.160 | 0.93 |
| 순차 읽기 | 13.306 | 13.151 | 1.01 |
| 순차 쓰기 | 13.795 | 13.630 | 1.01 |
| fsync 포함 쓰기 | 287.633 | 291.306 | 0.99 |

비교 기준은 제품 원본이 아닌 e7f... 실험판이다. 고정 파일 전송의 3.30배는
실제 통합에서 재현되지 않았다. 경로 처리와 혼합 이벤트 대기 비용이 포함되며
원인별 시간을 아직 분리 계측하지 않았다. fsync 값은 공유 디스크의 상태가
달라 이전 날짜/세션의 표와 직접 비교하면 안 된다.

별도 진단에서 notification 1129회 중 단순 경로 성공이 1078회였다. 새 경로가
실행되지 않아서 나온 결과는 아니다. 측정 중에는 진단 출력을 껐다.

최종 SHA-256은 `bc19cebeaf418d194fd2c217d438bbe4a633dda0782fae7c5e7ac69043d30680`.
ARM64 엔진 빌드와 시험 APK 생성·설치, Debian의 84개 workload 결과 내용 검증이
통과했다. 확장한 stat-entry-test는 기존 실험판과 통합판 모두에서 읽기 전용 FD,
CLOEXEC/F_GETFL, dirfd, symlink/O_NOFOLLOW, 오류 포인터·페이지 경계, 동시 자식
3개와 반복 중인 자식 10개 종료, guest seccomp open 거부를 통과했다.
엔진 빌드에는 기존 upstream 경고가 있으며 새 전송 파일에서는 경고가 없었다.

이 통합은 성능 목표를 달성하지 못해 기본으로 채택하지 않는다. 다음 변경은
실제 작은 파일 작업에 남은 fstat/close 정지 비용의 분리 측정과 축소가 우선이다.
FD 전달 자체의 가능성과 전체 작업의 개선을 구분한다.

## 요청 수신 스레드 실험

`PROOT_BUILD_FASTSTAT=1 PROOT_BUILD_NOTIFY=1` 빌드에서
`PROOT_OPEN_NOTIFY=1 PROOT_OPEN_NOTIFY_THREAD=1`을 함께 지정하면 별도
스레드가 notification을 수신·처리한다. 기본 ptrace 루프는 blocking waitpid로
돌아가며, notification마다 ppoll과 pipe timer 설정을 수행하지 않는다.
옵션을 생략하면 기존 방식이다. 제품 기본 payload는 바꾸지 않는다.

RECV만 상태 잠금 밖에서 수행한다. 경로 변환·확장·talloc·Tracee 접근과
메인 루프의 상태 변경·삭제는 같은 mutex로 직렬화한다. 종료 시에는 잠금을
놓고 private signal로 RECV를 깨워 worker를 join한 뒤 tracee 정리를 수행한다.
신호가 RECV 진입 직전에 도착하는 경쟁을 피하기 위해 worker 종료 확인까지
깨우기를 반복한다. worker의 치명적 전송 오류는 추적 중인 자식을 종료하고
메인 waitpid를 깨운다. 스레드 모드에서는 비동기 SIGUSR1/2 talloc 출력이
동시 메모리 접근을 일으키지 않도록 비활성화한다.

후보 SHA-256:
`c40fb3aef399b62649389b7c5b84b5b988b8f4eede706a6d8b2d40802efa08af`.
ARM64 빌드·별도 APK 설치·시작/종료 및 기존 raw syscall 회귀가 통과했다.
raw 회귀는 동시 자식 읽기, 자식 강제 종료, 잘못된 포인터, dirfd/링크,
FD flags와 guest seccomp 거부를 포함한다.
`thread-test.py`는 8개 스레드가 800회 경로·링크·dirfd 열기, fstat, 내용,
CLOEXEC를 검사한다. 새 방식·기존 방식·notification 비활성에서 모두 통과했다.
실행은 guest의 `python3 thread-test.py`이며 별도 시간 제한을 두어야 한다.

[개발 중 단회 확인](results/thread-spot-2026-09-16.json)은 작은 파일 읽기
143.744/144.650/137.369ms, import 171.097/177.540/176.538ms,
tar 723.862/746.882/749.147ms였다(기준/기존 notification/스레드 방식).
기준은 751f 실험판의 kernel-pipe 옵션을 끈 상태다. 반복 비교가 아니며
확정 배속이나 전체 향상으로 해석하지 않는다. 전 작업 반복 측정은 하지 않았다.

이 단계에서는 FIFO/장치 파일 거부, auxv 후처리와 guest ptracer 제한이 남았다.
새 배포판 설치, stock Android/F2FS, 전송 오류 강제 주입은 당시 검증하지 않았다.
따라서 일반 목적 rootless 후보의 완성이나 전체 2배 달성으로 보고하지 않는다.

## 기본 장치와 auxv 지원, 새 Debian 설치

notification 경로에서 `/dev/null`, `/dev/zero`, `/dev/full`, `/dev/random`,
`/dev/urandom`의 읽기 전용 열기를 지원한다. 경로 이름이 아닌 O_PATH로 고정한
inode의 문자 장치 major/minor를 확인한다. 번호는
[Linux 장치 목록](https://www.kernel.org/doc/Documentation/admin-guide/devices.txt)을
따른다. 임의 장치나 TTY, FIFO의 열기를 수신 스레드에서 실행하지 않는다.
일반 파일의 빠른 경로는 이 검사를 거치지 않는다.

자기 프로세스의 auxv 경로는 AT_EXECFN을 guest 실행 주소로 보정한 sealed memfd를
읽기 전용으로 전달한다. 응답 직후 guest가 실행되므로 auxv_fd만 설정해서는
후속 read를 추적할 수 없는 문제를 해결한다. 부분 read, pread, dup에서도 같은
값을 제공한다. **procfs의 inode·크기·readlink 표현을 보존하지 않는 스냅샷**이며
다른 프로세스의 auxv를 보정하는 기능은 아니다. 원본 auxv는 변경하지 않는다.

최종 후보 digest:
`14463e3bb1990b4d8e8eb0ed992332dbe796a3ad73c34d61f5fa63d471cbfc3c`.

- `special-test.py`: 다섯 장치, symlink, 읽기 전용/flags, FIFO 명시적 거부,
  auxv 3바이트 분할 읽기·pread·dup·seal 검사 통과. 스레드/기존 대기 방식 모두 확인.
- 같은 후보의 raw syscall 회귀와 8스레드/800회 열기 회귀 통과.
- 표준 Gradle 및 시험 APK 빌드 통과(170 tasks, 15 executed).
- [새 Debian 설치](../../../docs/ROOTLESS_FRESH_INSTALL_TEST.md): notification 스레드와
  stat/path/rename/fstat 가속을 켜고 기본 패키지·환경·하드링크 검증 통과,
  `OK (1 test)`, 87.019초. 임시 설치 디렉터리 잔여 0개 확인.

[검증 기록](results/special-validation-2026-09-16.json)을 남겼다. 성능 비교는
추가 실행하지 않았다. 설치 시간으로 배속을 계산하지 않는다. FIFO/TTY,
guest ptracer, stock Android/F2FS 및 전송 오류 주입은 남은 범위이며, 이 설치
성공을 전체 Linux 기능 검증으로 일반화하지 않는다. 제품 기본값은 바꾸지 않았다.

## 익명 파이프와 비대기 FIFO 열기

notification에서 익명 파이프의 읽기 전용 재열기와 `O_NONBLOCK`을 지정한
이름 있는 FIFO 열기를 지원한다. `O_PATH`로 고정한 inode가 FIFO 타입이면
원래 flags 및 fstatfs의 PIPEFS_MAGIC을 검사한다. 경로가 `/proc/self/fd/N`이어도
이름 있는 FIFO를 가리킬 수 있으므로 경로 문자열로 익명 여부를 판정하지 않는다.
원래 flags를 유지하며 dup으로 대신하지 않아 file status flags도 독립적이다.

[Linux 5.10](https://github.com/torvalds/linux/blob/v5.10/fs/pipe.c),
[6.1](https://github.com/torvalds/linux/blob/v6.1/fs/pipe.c),
[6.17](https://github.com/torvalds/linux/blob/v6.17/fs/pipe.c)의 fifo_open에서
익명 파이프는 writer 대기를 하지 않으며, 이름 있는 FIFO는 O_NONBLOCK을
지정해야 대기를 생략함을 확인했다. 따라서 공유 이벤트 처리 잠금을 잡은 채
이름 있는 blocking FIFO를 열지는 않는다. 해당 호출은 계속 EOPNOTSUPP다.

후보 digest:
`1495a9040f8ed05f38bf0a7ff095b1b53ade743f71f227058909b6ed42432003`.
[검증 기록](results/pipe-validation-2026-09-16.json):

- ARM64 엔진 빌드와 별도 APK 설치 통과.
- `pipe-test.py`: 익명 파이프 재열기·writer 없는 EOF·독립 flags·비대기 FIFO
  전송 및 blocking FIFO 거부를 스레드/기존 대기 방식에서 통과.
- 기존 `kernel-pipe-test.sh`: 20회 셸 파이프·조기 소비자 종료·프로세스 치환 및
  임시 루트의 dpkg 설치·제거 통과. PROOT_KERNEL_PIPE 옵션은 켜지 않았다.
- 장치/auxv와 8스레드/800회 파일 열기 회귀 통과.
- Android MCP를 사용했고 증분 설치 미지원 뒤 streamed 설치가 성공했다.

성능 비교, 새 배포판 설치와 Gradle은 이번 native 실험 패치에서 재실행하지
않았다. 앞 절의 새 설치 성공은 14463e 후보의 결과다. TTY·임의 장치·blocking
named FIFO·guest ptracer 및 stock Android/F2FS는 남은 범위이며 기본 적용하지 않았다.
