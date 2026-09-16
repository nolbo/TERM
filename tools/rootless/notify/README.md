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
