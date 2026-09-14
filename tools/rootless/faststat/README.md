# PRoot 내부 파일 조회 실험

상태: 개발용 실험. 기본 앱에 포함하거나 활성화하지 않는다.
전체 목표는 [재설계안](../../../docs/ROOTLESS_FILESYSTEM_REDESIGN.md)에 있다.

## 공통 구조와 모드 1

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
모드 1에서는 이 경로를 가속하지 않는다. 모드 2는 아래의 별도 상태 조회로
처리한다. glibc 내부 syscall 명령어 수정과 정적 ELF 가속은 구현하지 않았다.

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
`PROOT_NO_SECCOMP=1`도 예상 값 0이다. 모드 1에서는 `-0`,
`--link2symlink`, 추가 `-b`도 비활성화 조건이다.
속도 비교에서는 `PROOT_PERF`를 끈다. 별도 계측에서는 이를 1로 설정하고
stderr도 수집하여 bench 0과 bench 3000의 tracer 카운터 차이를 비교한다.

실험 tracer의 `PROOT_FASTSTAT=1` 실행에서는 mount/umount/pivot_root/chroot/
unshare/setns가 EOPNOTSUPP로 거부된다. 초기 매핑 조건이 바뀌는 기능을 아직
지원하지 않기 때문이다. DSO의 DISABLE은 이 tracer 제한을 해제하지 않는다.
일반 동작으로 돌아가려면 새 실행에서 tracer의 PROOT_FASTSTAT을 끈다.

## 모드 1 검증과 측정: 2026-09-14

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

## 모드 2: 현재 cwd·bind·fake-root 반영

`PROOT_FASTSTAT=2`는 `-0 --link2symlink`와 추가 bind를 유지한 채 일반 파일과
디렉터리의 stat/lstat/fstatat 및 64-bit 대응 함수를 처리한다. 호출마다 전용
prctl로 현재 tracee의 경로 변환 결과와 fake-root의 saved UID/GID를 받는다.
이후 같은 프로세스의 openat2(NO_SYMLINKS) → fstat → close로 조회하고 기존
fake_id0와 같은 소유자 보정을 수행한다. 절대 경로와 AT_FDCWD도 지원한다.

상태를 캐시하지 않으므로 chdir/fchdir, bind, fork 이후 UID/GID 변경을 다음
조회에 반영한다. 전용 요청만 seccomp 진입 단계에서 완료하며 일반 prctl의
반환 추적은 유지한다. 모드 1의 namespace syscall 차단은 모드 2에 적용하지
않고 기존 PRoot 동작을 따른다. 이것이 새 namespace 기능을 제공하지는 않는다.

검증되지 않은 l2s 내부 이름, 호스트 /proc·/sys·/dev, 비정규 객체, 해석 실패는 기존
syscall로 돌아간다. 이 경우 상태 조회 비용이 추가되어 느려질 수 있다.
경로 스냅샷과 커널 조회 사이의 rename 경쟁, 내부 FD와 다른 스레드의 FD 조작
경쟁까지 원자적으로 해결한 구현은 아니다. 기본 앱에는 활성화하지 않는다.

위 실행 예제에서 PROOT_FASTSTAT=2와 `-0 --link2symlink`, 필요한 `-b`를
지정한다. `bench-cwd 3000`은 AT_FDCWD를 측정한다.
`FASTSTAT_TEST_BIND`에 테스트용 bind 아래의 읽을 수 있는 파일 경로를 주면
절대/상대 경로와 symlink를 통한 bind 접근을 원래 syscall과 비교한다.

동일 redroid의 Debian/Ubuntu에서 C 회귀 검증과 `perf-guest.py`를 통과했다.
추가 검증은 cwd/fchdir, bind 별칭, fork 후 서로 다른 real/effective/saved
UID/GID, 잘못된 요청 크기·포인터·NUL 없는 패킷을 포함한다. 잘못된 포인터
시험의 PRoot `ptrace(PEEKDATA): I/O error` 경고는 의도한 EFAULT 검증이다.
모드 1 재검증, seccomp 끔, 호스트 fallback도 통과했다. 최초 모드 2에서는
하드링크 전환 이후 thread 조회가 대부분 fallback이었다. 아래 후속 구현은
이 객체도 가속하며 thread 조회의 hit 수를 별도로 검증한다.

Debian에서 `-0 --link2symlink`, /dev·/proc·/sys·/root·/workspace bind를
유지하고 7회 번갈아 측정했다. 회차마다 순서를 반전했고 warm-up 100회 후
일반 파일 3,000회를 조회했다. 카운터는 껐으며 같은 실험 PRoot와 DSO에서
DISABLE만 전환했다. 다음은 중앙값과 최소–최대다.

| 조회 | 고속 끔 | 모드 2 | 시간 감소 |
| --- | ---: | ---: | ---: |
| dirfd 상대 경로 | 177.806 ms (168.981–182.140) | 139.265 ms (134.888–141.360) | 21.7% |
| AT_FDCWD | 163.388 ms (159.626–166.140) | 126.631 ms (126.208–128.517) | 22.5% |

각 고속 측정은 hits=3000, fallbacks=0이었다. 별도 PROOT_PERF 진단에서
bench 0→3000의 enter_stop은 176→3176, exit_stop은 48→48이었다.
**호출당 tracer 진입 1회는 남고 반환 정지가 제거**되었다. 경로 canonicalization도
계속 필요하다. 모드 1의 7.18배 수치와 조건이 다르며, 패키지 설치나 전체
Rootless 성능 개선을 입증한 수치가 아니다.

표준 Gradle 검증은 BUILD SUCCESSFUL(138 tasks, 137 up-to-date), 게스트 C는
Clang -Wall -Wextra -Werror와 호스트 fallback 검증을 통과했다. 일반 PRoot
재빌드는 기존 payload digest와 일치했고, 실험 재빌드도 다음 SHA-256으로
재현되었다: `998253f4f4ace6c49d34bd7ab6f60c3d4650a3024ea079a34abfad07ab46d4e7`.

## 모드 2 후속: l2s 하드링크 조회

가상 링크 수를 매 호출의 경로 응답에 추가했다. 실행 옵션은 계속
`PROOT_FASTSTAT=2`이며 프로토콜 버전은 3, 패킷 크기는 4,128바이트다.
구버전 tracer와 새 DSO의 조합은 handshake 불일치로 비활성화된다.
구버전 DSO도 새 tracer의 버전 3을 지원하지 않아 비활성화된다.

link2symlink가 활성화되어 있고 변환된 경로의 마지막 이름이 `.l2s.`로
시작하며 유효한 `.NNNN` 접미사를 가진 경우에만 가속한다. 내부 경로의
조상 디렉터리 이름, 알 수 없는 형식, 0 링크 수는 기존 경로로 돌아간다.
커널 fstat가 일반 파일임을 확인한 뒤 스냅샷의 가상 링크 수를 적용한다.
실제 하드링크로 변환하거나 저장 형식을 바꾸는 migration은 수행하지 않는다.

검증 중 fork에 따라 fake_id0와 link2symlink의 callback 순서가 뒤집히는
기존 동작을 확인했다. l2s는 stat 전체를 덮어쓰므로 그 뒤에 fake_id0가
실행되는 경우에만 소유자 보정이 남는다. 새 dispatcher는 현재 tracee의
실제 순서를 반영한다. 기존 PRoot의 이 일관성 문제 자체를 고친 것은 아니다.

추가 C 검증은 다음을 포함한다.

- stat/lstat의 전체 메타데이터와 errno를 기존 syscall과 비교
- 링크 생성·삭제에 따른 링크 수 3→2→1 확인
- 하드링크를 4-thread로 800회 조회하고 **800회 모두 hit** 확인
- 부모·자식·손자 및 UID/GID 변경 이후 소유자 비교
- 확장 옵션 순서를 반대로 지정한 경우와 fake-root 없는 경우
- 구버전 tracer, seccomp 끔, 모드 1, 호스트 fallback 회귀 검증

API 35 ARM64 redroid의 Debian/Ubuntu에서 C 검증과 `perf-guest.py`를 통과했다.
잘못된 요청 포인터의 의도적인 EFAULT 경고 이외 최종 테스트 실패는 없었다.

`bench-hard 3000`은 링크 두 개를 가진 파일을 조회한다. Debian에서 기존
모드 2 측정과 같은 fake-root·기본 bind, warm-up 100회, 카운터 끔 조건으로
7회 측정하고 실행 순서를 매 회차 반전했다. 같은 최신 tracer/DSO에서
DISABLE만 바꾼 결과다.

| 조건 | 중앙값 | 최소–최대 |
| --- | ---: | ---: |
| 고속 끔 | 291.330 ms | 280.318–300.803 ms |
| 하드링크 가속 | 245.811 ms | 241.244–248.782 ms |

소요 시간 **15.6% 감소**, 매 실행 hits=3000/fallbacks=0이었다. 이는 이전
시제품의 실패 후 fallback 비용까지 비교한 수치나 전체 작업 성능 수치가 아니다.
일반 파일의 추가 3회 확인에서는 고속 켬 137.163 ms, 끔 178.187 ms 중앙값으로
기존 가속 효과가 유지됐다. 이 확인의 표본 수는 위 7회 측정과 다르다.

하드링크 bench 0→3000 진단에서 enter_stop=178→3178, exit_stop=50→50이었다.
canon_lstat=1501→43501로 **조회당 경로 확인 14회가 여전히 남는다**.
링크 수를 캐시하지 않으므로 경로 변환 후 링크 변경·rename과의 경쟁을
원자적으로 해결하지 않는다. close_range 등 내부 FD 경쟁과 일반 Android
보안 정책 검증도 계속 남는다.

최종 Clang 경고 검사, 호스트 fallback, 표준 Gradle 검증(138 tasks 중
137 up-to-date)을 통과했다. 실험 PRoot 재빌드는 SHA-256
`cb7d53b3f979a850678b512b61a62448b030bae616aec50d661447dde584e2bc`로
재현됐다. 제품 payload는 교체하지 않았다. Android MCP의 incremental 설치는
거부되어 streamed 설치로 성공했다. 검증 뒤 전용 APK와 임시 파일을 제거했다.

## 후속: 한 요청 안의 디렉터리 확인 재사용과 실제 작업 측정

모드 2의 RESOLVE 한 번 안에서 이미 확인한 디렉터리 경로를 최대 16개까지
재사용한다. 저장 공간은 tracer 스택에 있고, translate_path가 반환하면 즉시
참조를 지운다. 다른 요청·프로세스의 상태를 캐시하지 않는다. 실패와 심볼릭
링크는 저장하지 않고 HOST_PATH 확장 처리는 계속 수행한다. 마지막 openat2의
NO_SYMLINKS 검사도 유지한다. 관찰한 디렉터리가 심볼릭 링크나 파일로 바뀌어
커널 조회가 실패하면 기존 경로를 사용한다. 완전한 원자적 namespace 해석을
구현한 것은 아니다. 진단용 `PROOT_FASTSTAT_PATH_REUSE=0`으로 재사용만 끈다.

Debian/Ubuntu 회귀 검증, 모드 1, seccomp 끔, 재사용 끔 검증을 통과했다.
테스트에 경로 접두사의 symlink 교체 및 20단계 디렉터리를 추가해 이전 요청의
정보가 남지 않고 저장 공간의 상한을 넘는 경우도 처리하는지 확인했다.

하드링크 조회 3,000회(100회 warm-up)를 7회씩 순서를 반전해 비교했다.
같은 최신 PRoot/DSO에서 옵션만 전환했고 속도 측정 시 진단 출력은 껐다.

| 조건 | 중앙값 |
| --- | ---: |
| DSO 가속 끔 | 289.453 ms |
| 하드링크 가속, 디렉터리 재사용 끔 | 242.617 ms |
| 하드링크 가속, 디렉터리 재사용 켬 | 224.675 ms |

디렉터리 재사용으로 기존 가속 대비 **추가 7.4%**, 전체 가속 끔 대비 **22.4%**
시간이 감소했다. 진단에서는 추가 3,000회의 canon_lstat가 42,000→24,000으로,
**호출당 14→8회**가 됐다. tracer 진입 1회는 여전히 남는다.

[`workloads.py`](../workloads.py)는 자체 임시 입력을 만들고 준비·정리 밖의
전체 작업 시간을 측정한다. 사용자 저장소와 package DB는 수정하지 않는다.
Git 환경 변수도 제거해 외부 GIT_DIR로 작업이 넘어가지 않게 한다. 다음처럼
선택한 guest에서 실행한다. 기본 case에는 git-status도 포함되어 있으며
필요한 도구가 없으면 명시적으로 실패한다.

```sh
python3 -B /tmp/terminal-walk/workloads.py --count 1000 --rounds 1 \
  --case metadata --case mutations --case python-import --case tar-extract
```

DSO 환경을 유지하며 위 실행과 `PROOT_FASTSTAT_DISABLE=1` 실행을 번갈아
비교한다. `--coverage`는 자식에 PROOT_FASTSTAT_REPORT=1을 전달한다. 이 DSO
옵션은 정상 종료 시 공개 함수의 hit/fallback 수를 stderr JSON으로 출력한다.
원시 syscall과 libc 내부 미연결 호출은 세지 않으며, _exit로 종료한 자식도
보고하지 않는다. 속도 측정 때는 사용하지 않는다. parent 카운터는 측정 구간의
차이이고 child_reports는 보고한 자식의 시작부터 종료까지 합계다.
`guest_cpu_ms`는 guest와 자식의 CPU 시간으로 **tracer CPU는 제외**한다.

Debian에서 파일 1,000개, 고정 stdlib import 집합, warm 입력으로 7회씩
실행했다. pair마다 켬/끔 순서를 반전했으며 각 실행의 case 순서는 동일했다.
파일 크기·추출 내용·명령 성공을 검증했다. 중앙값은 다음과 같다.

| 실제 작업 | 가속 끔 | 가속 켬 | 시간 변화 |
| --- | ---: | ---: | ---: |
| 서로 다른 파일 정보 조회 | 65.384 ms | 50.921 ms | 22.1% 감소 |
| 생성·rename·삭제 | 373.869 ms | 370.123 ms | 1.0% 감소 |
| 새 Python 프로세스의 stdlib import | 198.633 ms | 198.112 ms | 0.3% 감소 |
| Python tarfile 압축 없는 tar 추출 | 902.811 ms | 909.565 ms | 0.7% 증가 |

뒤의 세 작업에서 유의미한 전체 성능 개선은 입증되지 않았다. 별도 적용률
진단에서 Python 자식은 hits=535/fallbacks=87, 생성·rename·삭제는 둘 다 0이었다.
공개 stat 함수 연결만으로는 open 및 쓰기 연산의 추적 비용을 줄이지 못한다.
7쌍의 [원본 측정값](results/path-reuse-2026-09-14.json)을 보존한다.

기기에 Git이 없어 git-status는 실행되지 않았다. 호스트에서는 5개 case와
외부 GIT_DIR/WORK_TREE/INDEX_FILE 격리 검증을 통과했으며 이는 guest Git 성능의
증거가 아니다. 10만 파일, cold cache, 4-worker 전체 작업, 오프라인 dpkg,
Git checkout 및 incremental build 측정은 아직 남아 있다.

표준 Gradle 검증과 실험 PRoot 재현 빌드가 통과했다. 실험 PRoot SHA-256은
`ff02af823e3bb032eb97ab142c9b446a3878c889ccea50aaa431198b31dbbb45`다.
Android MCP의 별도 APK로 검증했으며 기존 앱 payload와 세션은 교체하지 않았다.

## 제품 적용 전에 남은 일

장비는 SELinux unconfined, 기존 앱 seccomp 필터 없음으로 확인된 redroid다.
stock Android 앱에서 executable mapping과 신규 syscall의 허용 여부는 아직
입증하지 않았다. 특히 SIGSYS/프로세스 종료를 일으키는 플랫폼 제한은 errno
fallback과 다르므로 먼저 독립적인 테스트 프로세스에서 검사해야 한다.

이 DSO는 공개 glibc 함수만 연결하는 시제품이다. 내부 호출·정적 바이너리는
기존 PRoot 경로를 사용한다. 전체 가상 파일시스템 의미, 특수 /proc 객체,
guest가 전달받은 외부 FD, raw clone/fork 또는 다른 스레드의 close_range와
일시적 내부 FD의 경쟁까지 호환성을 보장하는 완성된 runtime이 아니다.
후속 단계에서 FD 소유권과 동시 경로 변경을 처리하고, 검증 가능한 상태 공유로
모드 2에 남은 호출당 tracer 진입을 제거해야 한다. 이 조건이 충족되기 전에는 기본 실행에
넣지 않으며 기본 `-0 --link2symlink` 옵션을 제거해서 가속하지 않는다.
