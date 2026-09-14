# PRoot 내부 파일 조회의 첫 실행 시제품

상태: 개발용 실험. 기본 앱에 포함하거나 활성화하지 않는다.
전체 목표는 [재설계안](../../../docs/ROOTLESS_FILESYSTEM_REDESIGN.md)에 있다.

## 구현한 범위

- `proot-faststat.patch`: 최초 seccomp 필터에 ARM64 전용 syscall 실행 주소를
  추가한다. 정확한 PC에서의 openat2/fstat/close만 PRoot의 추적을 생략한다.
  다른 주소와 syscall은 기존 필터로 내려간다. 상속된 OS 정책은 유지된다.
- `faststat.c`: glibc의 fstatat/fstatat64를 연결한다. **실제로 열린 dirfd와
  상대 경로**에 openat2 → fstat → close를 같은 프로세스에서 수행한다.
  BENEATH와 NO_SYMLINKS로 커널이 실제 조회 시점의 경계를 검사한다.
- 잘못된 사용자 포인터를 직접 역참조하지 않도록 자기 프로세스의
  process_vm_readv/writev로 경로와 결과를 복사한다. tracer와의 메모리 복사는 없다.
- 절대 경로, AT_FDCWD, 링크, 해석 제한 실패, 지원하지 않는 flags,
  AT_EMPTY_PATH, FD 부족 등은 원래 fstatat syscall로 처리한다.
  stat/lstat 및 64-bit 대응 함수도 기존 경로를 사용한다.
- 초기 prctl handshake는 대응하는 tracer의 seccomp 가속, fake-root 없음,
  link2symlink 없음, root 이외 bind 없음 조건을 확인한다. 불충족 시 비활성화한다.
  필터용 코드는 명시적으로 실험 빌드를 만들 때만 추가된다.

PRoot의 chdir/fchdir는 가상 cwd만 바꾸고 커널 cwd를 바꾸지 않는다.
따라서 AT_FDCWD를 무조건 직접 호출하면 잘못된 파일을 조회할 수 있다.
이번 시제품에서 이 경로는 의도적으로 가속하지 않는다. glibc 내부 syscall
명령어 수정, 정적 ELF, fake-root 상태 동기화와 가상 bind 처리는 아직 구현하지 않았다.
현재 제품의 `-0 --link2symlink` 세션은 이 시제품으로 가속되지 않는다.

trampoline은 PRoot loader/ELF/ld.so의 예약 주소와 별도의 16 KiB 영역을
MAP_FIXED_NOREPLACE로 확보한다. 충돌 시 기존 매핑을 덮어쓰지 않는다.
코드를 쓴 뒤 RX로 전환하며 RWX 매핑은 요청하지 않는다. 이 주소 예외는
같은 UID의 악의적인 guest를 격리하는 보안 장치가 아니다.

## 빌드와 실행

저장소의 기존 ARM64 Clang, NDK 데이터 및 고정 PRoot 입력을 사용한다.
게스트 DSO/테스트 프로그램은 ARM64 glibc 호스트에서 빌드한다.
이번 빌드의 DSO는 GLIBC_2.17, 테스트 실행 파일은 최대 GLIBC_2.34 심볼을 요구한다.
다른 호스트에서 빌드할 때도 `readelf -V`로 실제 요구 버전을 확인한다.

```sh
PROOT_BUILD_FASTSTAT=1 tools/rootless/build-proot-arm64.sh /tmp/faststat-payload
bash tools/rootless/faststat/build.sh /tmp/faststat-guest
tools/rootless/build-perf-test-apk.sh /tmp/faststat-payload /tmp/faststat-test.apk \
  android-runtime/src/main/rootless/arm64-v8a/proot
```

이미 확보한 입력에는 `PROOT_DOWNLOAD_CACHE`를 지정할 수 있다. 고정 digest
검사는 그대로 수행한다. 일반 빌드는 종전의 두 payload digest를 검증하고,
실험 빌드는 새 PRoot digest를 출력하며 loader의 기존 digest를 검증한다.
두 모드 모두 16 KiB LOAD 정렬을 검사한다. 실험 출력으로 제품 payload를 덮어쓰지 않는다.

별도 테스트 APK를 설치하고 해당 APK의 추출된 nativeLibraryDir에 있는 PRoot와
loader를 사용한다. 게스트 DSO/테스트 파일은 rootfs의 전용 임시 디렉터리에
복사한다. guest ELF는 기존 APK loader로 실행하며 app-data execve를 허용하지 않는다.
rootfs와 임시 파일에 접근하는 앱 UID에서 다음 형식으로 실행한다.
`PROOT`, `PROOT_LOADER`, `ROOTFS`, `PROOT_TMP_DIR`은 테스트 환경의 절대 경로다.

```sh
export PROOT_LOADER PROOT_TMP_DIR
export PROOT_FASTSTAT=1 PROOT_NO_MOUNTINFO=1
"$PROOT" -r "$ROOTFS" -w /tmp/terminal-faststat \
  /usr/bin/env LD_PRELOAD=/tmp/terminal-faststat/libproot-faststat.so \
  /tmp/terminal-faststat/faststat-test 1 test
"$PROOT" -r "$ROOTFS" -w /tmp/terminal-faststat \
  /usr/bin/env LD_PRELOAD=/tmp/terminal-faststat/libproot-faststat.so \
  /tmp/terminal-faststat/faststat-test 1 bench 3000
```

테스트 프로그램의 첫 인수는 예상 enabled 값이며 불일치 시 실패한다.
`PROOT_FASTSTAT_DISABLE=1`을 guest 환경에 추가하면 같은 DSO의 고속 경로를
끄고 첫 인수를 0으로 비교할 수 있다. 기존 PRoot, `PROOT_FASTSTAT=0`,
`PROOT_NO_SECCOMP=1`, `-0`, `--link2symlink`, 추가 `-b`도 예상 값 0이다.
속도 비교에서는 `PROOT_PERF`를 끈다. 별도 계측에서는 이를 1로 설정하고
stderr도 수집하여 bench 0과 bench 3000의 tracer 카운터 차이를 비교한다.

실험 tracer의 `PROOT_FASTSTAT=1` 실행에서는 mount/umount/pivot_root/chroot/
unshare/setns가 EOPNOTSUPP로 거부된다. 초기 매핑 조건이 바뀌는 기능을 아직
지원하지 않기 때문이다. DSO의 DISABLE은 이 tracer 제한을 해제하지 않는다.
일반 동작으로 돌아가려면 새 실행에서 tracer의 PROOT_FASTSTAT을 끈다.

## 검증과 측정: 2026-09-14

Android MCP로 별도 테스트 APK를 사용해 API 35 ARM64 redroid의 기존
Debian/Ubuntu rootfs에서 시험했다. 앱 업데이트나 살아 있는 사용자 세션의
종료 없이 게스트 임시 파일만 추가했다. 다음 검증을 통과했다.

- Debian/Ubuntu 고속 실행: 메타데이터 필드와 errno, hardlink, 상대/절대/
  dangling symlink, 디렉터리 FD, AT_EMPTY_PATH, rename, 잘못된 flags/포인터,
  페이지 경계의 문자열, 4-thread 조회, fork, exec 재초기화, FD 고갈 fallback.
- 추가 seccomp 필터가 openat2에 EPERM을 반환하면 고속 호출을 우회하지 않고
  원래 fstatat로 돌아간다. 이 제한은 별도 자식 프로세스에서 설치해 검사했다.
- Debian의 fake-root, link2symlink, 추가 bind, tracer 옵션 끔, seccomp 가속 끔:
  고속 경로가 비활성화되고 같은 회귀 검증을 통과했다.
- 호스트에서 대응 tracer 없이 DSO를 로드한 fallback 회귀 검증도 통과했다.

7회 번갈아 실행하고 짝수 회차에는 순서를 반대로 했다. 매 회차 100회
warm-up 후 같은 파일에 dirfd 기반 fstatat 3,000회를 수행했다. 속도 계측 시
PRoot 카운터는 껐고, 모든 조건에서 같은 테스트 실행 파일과 DSO를 사용했다.
baseline도 fake-root와 link2symlink 없이 root만 매핑했다.

| 조건 | 중앙값 | 7회 최소–최대 |
| --- | ---: | ---: |
| 기존 제품 PRoot + DSO 고속 끔 | 91.174 ms | 86.525–93.412 ms |
| 실험 PRoot + DSO 고속 끔 | 90.611 ms | 88.891–92.696 ms |
| 실험 PRoot + DSO 고속 켬 | 12.705 ms | 12.469–12.963 ms |

이 제한된 반복 조회에서 기준 대비 약 **7.18배**였다. 고속 실행의 3,000회는
모두 고속 경로였고 fallback은 0회였다. CPU Native 대비나 현재 기본 Rootless
세션의 전체 속도 개선 수치가 아니다. git/패키지 설치 등의 전체 성능은 미측정이다.

별도 진단에서 고속 bench 0/3000은 둘 다 enter_stop=68, exit_stop=36,
vm_read=35, vm_write=21이었다. 추가 조회 3,000회가 tracer 정지나 tracer의
메모리 접근을 증가시키지 않았다. 자기 프로세스의 메모리 복사 syscall과
openat2/fstat/close 비용은 남는다.

일반 PRoot 재빌드는 종전 payload SHA-256 두 개와 일치했다. Clang의
`-Wall -Wextra -Werror`, 셸 문법 검사와 다음 표준 검증도 통과했다.
Gradle은 138 tasks 중 137개를 up-to-date로 재사용했다.
실험 PRoot도 별도 임시 디렉터리에서 재빌드하여 같은 바이너리임을 확인했다.

```sh
./gradlew :android-app:assembleDebug :android-runtime:testDebugUnitTest :image:test --console=plain
```

APK incremental 설치는 기기에서 거부되어 도구가 streamed 설치로 완료했다.
로컬 adb 전송은 샌드박스의 소켓 제한으로 실패해 Android MCP push_file을 사용했다.
초기 고정 주소의 loader 충돌과 가상 cwd 가정 오류는 구현 중 발견해 수정했다.
검증 후 별도 테스트 APK, 기기에 전송한 두 파일과 두 배포판의 실험용 임시
디렉터리를 제거했다. 기존 앱과 사용자 세션은 교체하거나 종료하지 않았다.

## 제품 적용 전에 남은 일

장비는 SELinux unconfined, 기존 앱 seccomp 필터 없음으로 확인된 redroid다.
stock Android 앱에서 executable mapping과 신규 syscall의 허용 여부는 아직
입증하지 않았다. 특히 SIGSYS/프로세스 종료를 일으키는 플랫폼 제한은 errno
fallback과 다르므로 먼저 독립적인 테스트 프로세스에서 검사해야 한다.

이 DSO는 공개 glibc 함수만 연결하는 시제품이다. 내부 호출·정적 바이너리는
기존 PRoot 경로를 사용한다. 전체 가상 파일시스템 의미, 특수 /proc 객체,
guest가 전달받은 외부 FD, raw clone/fork 또는 다른 스레드의 close_range와
일시적 내부 FD의 경쟁까지 호환성을 보장하는 완성된 runtime이 아니다.
후속 단계에서 FD 소유권과 가상 cwd, fake-root/하드링크 상태, bind 경계를
공유하는 dispatcher를 구현해야 한다. 이 조건이 충족되기 전에는 기본 실행에
넣지 않으며 기본 `-0 --link2symlink` 옵션을 제거해서 가속하지 않는다.
