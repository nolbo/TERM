# PRoot 내부 파일 조회 실험

상태: 개발용 실험. 기본 앱에 포함하거나 활성화하지 않는다.

추가 옵션 `PROOT_FSTAT_ENTRY=1`은 native ARM64의 일반 파일·디렉터리 fstat를
진입 시 완료한다. `PROOT_BUILD_FASTSTAT=1` 빌드에 포함되며 실행 시 명시적으로
켜야 한다. 열린 inode의 현재 정보를 읽으므로 삭제·FD 재사용·크기 변경을
캐시하지 않는다. procfs 접근 실패·특수 파일·지원하지 않는 확장과 출력 버퍼
오류는 기존 처리로 돌아간다. stat/path/rename 옵션과 함께 사용한
[비교 결과](results/fstat-entry-2026-09-16.json)는 작은 파일 읽기 시간을
159.768→140.570ms로 줄였지만 순차 I/O는 거의 같았다. 비교 기준은 이전
e7f... 실험판이며 제품 원본 대비 배속이 아니다. 혼합 작업의 metadata 악화는
[별도 반복 시험](results/fstat-metadata-audit-2026-09-16.json)에서 재현되지
않았으며 두 결과를 모두 보존한다. [10단계 기록](../../../docs/ROOTLESS_2X_PROGRESS.md)에
오류·권한·seccomp·Debian 설치 검증 범위를 정리했다.

전체 설계는 [재설계안](../../../docs/ROOTLESS_FILESYSTEM_REDESIGN.md), 실측에 따른
현재 목표와 한계는 [실현 가능성 평가](../../../docs/ROOTLESS_FEASIBILITY.md)에 있다.

## 공통 구조와 모드 1

- `proot-faststat.patch`: 최초 seccomp 필터에 ARM64 전용 syscall 실행 주소를
  추가한다. 정확한 PC에서의 openat2/openat/fstat/close/unlinkat만 PRoot의 추적을 생략한다.
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
`PROOT_FASTSTAT=2`이며 이 단계의 프로토콜 버전은 3, 패킷 크기는 4,128바이트다.
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

## 후속: 확인된 마지막 파일 부재의 직접 응답

현재 프로토콜은 버전 4, 패킷은 4,132바이트다. tracer가 이번 경로 해석에서
마지막 host 경로를 lstat하여 ENOENT를 확인했고, 최종 변환 경로도 정확히
동일할 때만 오류를 응답에 포함한다. l2s backing 파일과 특수 경로는 제외한다.
이 관찰은 한 요청 동안만 존재하며 다음 요청에 재사용하지 않는다. 게스트는
출력 stat 버퍼를 건드리지 않고 ENOENT를 반환한다. 중간 경로 오류, 다른 errno,
unsupported 응답은 계속 기존 syscall을 사용한다.

`openat2`의 ENOENT를 직접 반환하지 않는다. 추가 seccomp 필터가 존재하는
파일에도 그 오류를 만들 수 있기 때문이다. 요청의 result_errno는 -1로
초기화하며, 유효한 응답이 0 또는 ENOENT로 바꾼 경우에만 사용한다. 필터가
private prctl을 응답 없이 성공 처리해도 원래 guest 절대 경로를 host 경로로
오인하지 않고 기존 syscall로 돌아간다. 진단용 `PROOT_FASTSTAT_NEGATIVE=0`은
이 오류 응답만 끈다. 구버전 tracer/DSO 조합은 버전 불일치로 비활성화된다.

negative_hits는 전체 hits의 부분집합이며, 파일 부재를 고속 경로에서 처리한
횟수다. PROOT_FASTSTAT_REPORT=1의 JSON에도 포함된다. `bench-missing 3000`은
100회 warm-up 뒤 없는 파일을 3,000회 조회하고 errno 및 처리 횟수를 검사한다.

같은 최신 PRoot/DSO, fake-root·기본 bind, 진단 출력 끔 조건으로 7회씩
순서를 반전하여 측정했다. [원본 결과](results/negative-reply-2026-09-14.json)를
보존한다. 중앙값은 다음과 같다.

| 조건 | 없는 파일 3,000회 | 고정 Python import |
| --- | ---: | ---: |
| DSO 가속 끔 | 145.877 ms | 197.301 ms |
| 가속 켬, 오류 응답 끔 | 282.875 ms | 199.754 ms |
| 가속 켬, 오류 응답 켬 | 122.277 ms | 195.842 ms |

오류 조회는 기존 실험의 중복 fallback보다 **2.31배**, 전체 가속 끔보다 시간
**16.2% 감소**다. 기존 실험의 실패 조회가 일반 경로보다 훨씬 느렸다는 것도
측정으로 확인됐다. Python 전체 시간은 기존 실험 대비 약 2%, 전체 가속 끔
대비 약 0.7% 감소하여 큰 전체 성능 향상은 아직 입증되지 않았다.

별도 Python 적용률 진단은 hits=617/fallbacks=5/negative_hits=82였다. 이전
535/87에서 마지막 파일 부재 82회를 직접 처리하게 됐다. bench 0→3000 진단은
enter_stop=176→3176, exit_stop=48→48로 반환 정지 증가가 없었다. 경로 해석 및
요청당 tracer 진입, l2s의 실패 readlink 확인은 여전히 남는다.

Debian/Ubuntu C 및 Python 회귀 검증을 통과했다. C 검증에는 존재하지 않는
파일의 출력 버퍼 보존·잘못된 출력 포인터와 errno 순서, 파일 생성·삭제 직후의
조회, openat2에 EPERM/ENOENT를 주입하는 seccomp 필터, private 요청을 응답 없이
성공 처리하는 필터를 포함했다. 모드 1, 오류 응답 끔, 디렉터리 재사용 끔,
구버전 tracer, 호스트 fallback도 통과했다. 포인터 오류 주입의 PRoot 경고는
의도한 EFAULT 검증이다.

최종 Clang -Wall -Wextra -Werror, 표준 Gradle 검증 및 실험 재현 빌드를
통과했다. 실험 PRoot SHA-256은
`b7c961c8ac24140491653711d177d835c8f733b8cb815b4b8abf59809c7aca77`이다.
기본 앱 payload는 교체하지 않았다. 일반 Android 앱 정책 검증은 계속 남는다.

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

## open 경로 중복 제거: 2026-09-14

실험 PRoot core에서 open/openat/openat2 처리 중 자신이 방금 주입한 경로의
정확한 주소와 문자열을 한 syscall entry 동안만 재사용한다. 원래 guest
포인터와 다음 호출에는 적용하지 않는다. l2s의 FD 기록을 먼저 수행한 뒤
canonicalize가 방금 확인한 일반 파일의 중복 readlink도 생략한다.
`PROOT_FASTSTAT_OPEN=0`으로 두 변경을 끌 수 있다. DSO와 무관하게 적용되며
일반 빌드에는 포함하지 않는다. 커널 open, libc 취소 지점, syscall 추적 수는
그대로다. 전체 프로그램의 경쟁 조건 호환성을 증명하는 변경은 아니다.

`build.sh`가 추가로 만드는 `open-test`는 raw openat의 flags/오류/링크,
dup, fork와 SCM_RIGHTS 전달, libc FIFO open 취소를 확인한다. ARM64 호스트와
Android MCP의 Debian/Ubuntu에서 통과했다. 같은 소스의 정적 ELF도 두
배포판에서 DSO 없이 통과했다. 기존 stat 및 Python 회귀도 통과했다.

[원본 결과](results/open-path-2026-09-14.json)의 7쌍 중앙값은 raw
openat/pread/close 3,000회 170.642 → 164.287 ms였다. 최종 통합 가속은
metadata 65.857 → 50.129 ms, mutations 375.664 → 373.338 ms,
Python import 200.391 → 193.509 ms, tar 추출 904.534 → 896.644 ms다.
미세한 차이를 확실한 전체 작업 개선으로 해석하지 않는다. 최초 5배/2배는
미달성이며 목표 변경의 근거와 남은 재설계는 실현 가능성 평가에 기록했다.

Clang `-Wall -Wextra -Werror`, 호스트 open 시험, 표준 Gradle 명령
(`:android-app:assembleDebug :android-runtime:testDebugUnitTest :image:test
--console=plain`, JDK 17)은 통과했다. Gradle은 138 tasks 중 137 up-to-date였다.
실험 재현 빌드는 측정 payload와 동일한 SHA-256
`8b7abd9c555a0246f6aeea7a9fe0c457a17c07a552e0cd6ede6c7b0de534d932`였다.
loader는 기존 digest와 일치했다. 기본 앱 payload는 교체하지 않았다.
최종 stat 재실행은 처음에 필수 인자를 누락해 실패했으며, `1 test`로
수정한 실행은 두 배포판에서 `PASS enabled=1 hits=831 fallbacks=11`이었다.
이는 코드 회귀가 아닌 시험 호출 오류다. 검증 후 전용 APK와 이번 임시
파일을 제거했다. stock 정책 및 UI 시험을 통과했다고 주장하지 않는다.

## Native-link stat entry 실험: 2026-09-15

`PROOT_STAT_ENTRY=1`은 실험 PRoot core에서 ARM64 `fstatat64`(커널 번호 79)를
진입 시 완성한다. `PROOT_FASTSTAT`나 DSO preload를 요구하지 않는다.
`-0`과 일반 bind를 유지하되 l2s 확장이 없는 새 저장 방식을 대상으로 한다.
성공 시 종료 정지를 생략하고, 미지원 flags/객체/확장 및 메모리 쓰기 실패는
기존 kernel syscall과 종료 callback을 수행한다. 일반 커널 경로 해석과
진입 정지는 남는다. 실제 앱 payload에는 적용하지 않았다.

`build.sh`의 정적 `stat-entry-test`를 `-0`, l2s 없는 guest에서 실행한다.
Debian/Ubuntu, seccomp 가속 끔, mountinfo 끔의 회귀가 통과했다. 기존 l2s의
fork 후 소유자 동작은 이 native-link 시험의 기대와 달라 양쪽 비교에서
실패했으므로 지원 범위를 혼동하지 않는다. 상세 결과·수정 이력·미완료 목표는
[2배 목표 진행 기록](../../../docs/ROOTLESS_2X_PROGRESS.md)에 있다.

최종 빌드 SHA-256은
`ac32b09ebefbb3497616239949d0de67fe3e5a3d507d5c1f81c575bcb3fae2eb`다.
최종 측정 원본은 [stat-entry](results/stat-entry-2026-09-15.json), tracer
정지와 원격 메모리 계측은 [counters](results/stat-entry-counters-2026-09-15.json)다.
초기 오류 빌드의 속도 수치는 최종 결과에 포함하지 않았다.
재현 빌드는 같은 digest였고 loader digest도 기존 값과 일치했다. Clang 경고
검사와 표준 Gradle 명령(JDK 17, 138 tasks 중 137 up-to-date)이 통과했다.
Android MCP로 검증한 뒤 별도 APK와 이번 임시 파일을 제거했다. 시험 바이너리
전송은 자동 검토에서 한 번 거부되었고 사용자의 명시적 승인 후 성공했다.
APK incremental 설치가 지원되지 않아 도구의 streamed 설치로 성공했다.

## Kernel path stat 실험: 2026-09-15

STAT_ENTRY에 `PROOT_STAT_KERNEL=1`을 더하면 일반 경로의 구성 요소별
PRoot 조회를 kernel openat2(NO_SYMLINKS)로 대체한다. 성공한 O_PATH FD에
fstat을 수행한 뒤 닫고 stat entry 결과를 전달한다. 미지원 경로는 기존
처리를 유지한다. DSO를 요구하지 않으며 기본 앱에서는 켜지 않는다.

상위 symlink를 숨길 수 있는 중첩 binding은 가속에서 제외한다. 최상위
binding, absolute/cwd 경로, 링크 fallback과 readonly output, UID 변화,
guest seccomp는 정적 회귀 시험으로 확인했다. `stat-entry-test /workspace`는
별도의 임시 디렉터리로 binding 경로를 시험한다.

[측정](results/stat-kernel-2026-09-15.json)의 metadata는 2.61배였고,
나머지 작업은 1.08~1.17배였다. 전체 목표 달성을 주장하지 않는다.
최종 실험 SHA-256은
`511529bff26b6c68a0d467af494594b897fc33bd274e25064f35ad1577f28832`다.
재현 빌드에서 같은 digest를 확인했다. [별도 계측](results/stat-kernel-counters-2026-09-15.json)은
stat entry만 사용한 경우와 비교해 canonical lstat 24,036 → 14,118회,
path translation 5,513 → 3,257회를 기록했다. 이 수치는 입력 준비까지 포함하며
커널 안의 경로 탐색 비용이 사라졌다는 의미가 아니다.
Clang 경고 검사, 독립 Android 시험 APK 빌드와 표준 Gradle 검증을 통과했다.
공간 확보와 JUnit 시험 반환형 수정 후 기본 payload의 Debian 새 설치 시험은
통과했다(`OK (1 test)`, 89.251초). 같은 해시의 실험 가속 payload로도 새 Debian 설치 시험을
통과했다(`OK (1 test)`, 90.722초). 두 설치 시간은 단일 실행으로 배속 비교가 아니다. [절차와 결과](../../../docs/ROOTLESS_FRESH_INSTALL_TEST.md)를 참조한다.

## 일반 경로 가속 실험: 2026-09-15

STAT_ENTRY/STAT_KERNEL과 함께 `PROOT_PATH_KERNEL=1`을 주면 일반 open·파일
변경 경로에서 부모의 커널 검사와 최종 이름 검사를 사용한다. 옵션은 기본 꺼짐이다.
최종 실험 PRoot SHA-256은
`a19c562a50f26102415347e63118d23d3bf252134b68fa4693c5165ba75b6326`이다.

`sh path-kernel-test.sh [/workspace]`는 guest 안에 소유한 임시 트리만 만들고
정리한다. 읽기 제한 경로의 생성·쓰기, rename/link/unlink, dangling·상위 링크,
점·슬래시 오류 경로를 검증한다. 권한 모드 출력은 기존 fake-root의 지속되는
권한 보정 동작을 관찰하기 위한 것이며 복원 성공 assertion이 아니다.

[7회 교대 측정](results/path-kernel-2026-09-15.json)과
[별도 카운터](results/path-kernel-counters-2026-09-15.json)를 기록했다.
metadata 2.59배, 파일 변경 1.16배, import 1.12배, tar 1.23배이며
전체 목표는 미달성이다. [5단계 결과](../../../docs/ROOTLESS_2X_PROGRESS.md)에
검증 범위와 남는 제한을 기록했다.

## rename 진입 시 완료 실험: 2026-09-15

`PROOT_MUTATION_ENTRY=1`은 일반 파일의 renameat/renameat2(flags=0)를 tracer에서
완료하고 종료 정지를 생략한다. 디렉터리와 다른 flags는 기존 처리를 사용한다.
unlink/link는 기존에 종료 정지가 없어 이 가속에서 제외했다. 기본값은 꺼짐이다.

최종 PRoot digest는
`e7f7f3e6cbebb90d121a7c0ad4ad0b4e10a655e30e30e47d0239602ac7ea5a9c`다.
[측정](results/mutation-entry-2026-09-15.json)에서 파일 변경은 기존 대비
1.19배, 이전 경로 가속 대비 약 5.7% 시간 단축이었다. 다른 작업의 추가
향상은 확인되지 않았다. [계측](results/mutation-entry-counters-2026-09-15.json)에서
종료 정지 1,000회가 줄었다. 전체 2배 목표는 남아 있다.

확장한 stat-entry-test는 이동 오류·cwd·seccomp 거부를 검사하며 Debian/Ubuntu와
seccomp 가속 끔에서 통과했다. 동일 payload의 새 Debian 설치도 통과했다.
[설치 절차](../../../docs/ROOTLESS_FRESH_INSTALL_TEST.md)를 참조한다.

## 파이프 보정 생략 실험

faststat 실험 빌드에 `PROOT_KERNEL_PIPE=1`을 주면 close 추적과 auxv/netlink
상태 정리는 유지하면서 pipe shadow 생성만 생략한다. 기본값은 꺼짐이다.
실제 읽기 끝을 모두 닫으면 커널의 SIGPIPE/EPIPE 동작을 따르므로,
소비하지 않는 프로세스 치환의 writer 오류를 기존처럼 숨기지 않는다.

[112개 측정](results/kernel-pipe-2026-09-16.json)에서 직전 fstat 실험판 대비
작은 파일 읽기는 141.682→136.113ms였다. 효과가 작고 다른 항목의 악화도
관찰돼 기본 적용하지 않는다. close 전체 생략 실험의 효과를 이 옵션에
전용하지 않는다. 후보 digest는
`751f184bab448185a356621e1137efd31b1ec7e46a004f26884599a4a1e67b60`이다.

게스트에서 `bash kernel-pipe-test.sh`로 파이프 전달·조기 소비자 종료·프로세스
치환을 20회 검사하고, 임시 루트에 자체 패키지를 설치·제거한다. 시험은
Android MCP에서 옵션을 켜고 통과했다. 실제 패키지 DB는 변경하지 않는다.
새 배포판 설치, maintainer script, 네트워크 FD 재사용의 포괄 검증은 미실행이다.

## 내부 호출 필터와 fstat 진입 가속의 결합 수정

`PROOT_FASTSTAT=2`와 `PROOT_FSTAT_ENTRY=1`을 함께 켜면, 내부 전용 PC의
fstat도 일반 fstat 진입 필터에 먼저 잡히는 충돌이 있었다. 전용 PC/architecture/
syscall 검사를 먼저 배치해 의도한 직접 실행을 복구했다. 예외의 syscall 범위를
넓히지 않았으며 다른 주소의 fstat 및 전용 주소의 getuid는 계속 추적한다.
게스트가 추가한 seccomp 거부도 그대로 적용된다. 이 예외는 보안 격리 수단이 아니다.

`trampoline-test.c`는 별도 DSO가 만든 전용 호출 영역을 사용한다. 일반 fstat의
가상 소유자와 내부 fstat의 실제 소유자를 비교해 경로 혼선을 검출하고,
삭제된 inode의 크기를 128회 변경해 새 메타데이터를 확인한다. 자식에서 추가한
seccomp 필터가 두 fstat 경로를 모두 EPERM으로 거부하는지도 검사한다.
호스트 UID 값 자체는 출력하지 않는다. fake-root인 모드 2 실행을 요구한다.

`faststat/build.sh`가 이 시험을 함께 빌드한다. 시험 APK 생성 시
`PROOT_TEST_FASTSTAT_DIRECTORY`를 그 출력 디렉터리로 지정하면 DSO와 두
driver가 APK에 포함된다. 추출된 시험 nativeLibraryDir만 별도 guest 시험 경로에
bind해 실행할 수 있으며 제품 APK나 사용자 rootfs에 파일을 설치하지 않는다.

[검증 기록](results/trampoline-order-2026-09-16.json):

- 기존 1495a9... 엔진에서 새 회귀가 내부 소유자 검사에 실패해 문제 재현.
- 수정 9312f4... 엔진에서 통과. notification 스레드를 함께 켠 경우도 통과.
- 기존 `faststat-test 1 test` 통과: hits=831, fallbacks=11. 잘못된 포인터 시험의
  PEEKDATA 경고는 의도된 EFAULT 검사다. exec 확인의 bench 0은 성능 측정이 아니다.
- ARM64 엔진 빌드, guest DSO/driver의 경고 오류 처리 빌드, APK 서명·설치 통과.
- Android MCP incremental 설치 실패는 streamed 설치로 해결했다.

성능 비교와 새 배포판 설치는 반복하지 않았다. Android 모듈을 변경하지 않아
Gradle도 재실행하지 않았다. 이 수정은 fstat/close 전체를 프로세스 내부로 옮긴
구현이 아니며, 전체 2배 달성을 의미하지 않는다. 기본 payload는 유지했다.

### 스레드별 공유 자격 정보로 fstat 직접 실행

`PROOT_FASTFD=1`을 tracer와 guest 환경 양쪽에 지정하고 기존 모드 2 DSO를
preload하면 일반 파일·디렉터리의 interposed `fstat/fstat64`가 전용 syscall
PC에서 커널 정보를 직접 읽는다. 파일 크기·inode·FD 번호를 캐시하지 않는다.
스레드별 sealed-size memfd에는 실제/가상 UID·GID만 저장한다. tracer는 재개
직전에 saved UID/GID를 갱신하고, guest는 atomic sequence로 일관된 값을 읽는다.
메모리 출력은 process_vm_writev로 처리하며 지원하지 않는 대상·오류는 기존
syscall로 복귀한다. 상위 seccomp 거부는 우회하지 않는다.

페이지는 각 Linux thread에 독립적이다. fork 자식은 PID 변경으로 다시 연결하고,
exec 시 tracer가 기존 매핑을 해제한다. guest pthread destructor와 tracer talloc
소멸자는 각각 자기 매핑을 해제한다. 페이지 크기 변경 seal을 먼저 확인해
외부 truncate가 tracer에 SIGBUS를 일으키는 것을 방지한다. 이 협력 프로토콜은
PRoot를 보안 격리 경계로 바꾸지 않는다.

2026-09-16 Android MCP의 기존 ARM64 Debian 시험 환경에서 엔진
`a25b410f7902d969b05800596e3be63639c6a07dc8d743092070654ffc42fabf`와
새 `fd-test`를 검증했다. notification 비활성/스레드 활성 모두 종료 코드 0,
각 hits=651이었다. 파일 크기·unlink, 8스레드의 독립 saved-ID 변경, fork의
자격 정보 변경과 부모 유지, exec 재초기화, 디렉터리, 파이프 fallback,
dup2 재사용, 닫힌 FD, 읽기 전용/잘못된 출력 주소, guest seccomp fstat 거부,
memfd 생성 거부 시 fallback, seal 누락·잘못된 ABI 버전 거부를 검사했다.
기존 path-stat 회귀도 fastfd 활성 상태에서 hits=831/fallbacks=11로 통과했다.
이 카운터는 경로 실행 증거이며 속도 개선 수치가 아니다.

엔진·guest 빌드와 시험 APK 서명·설치가 통과했다. incremental 설치 미지원은
streamed 설치로 해결했다. DSO는 pthread API 때문에 현재 빌드에서 glibc 2.34
이상을 요구한다. static ELF와 libc 내부 hidden 호출은 interposition 대상이
아니다. 기본 제품 payload/환경은 바꾸지 않았다. 이번 단계에서 성능 비교,
새 Debian 설치, Android Gradle 빌드는 실행하지 않았다. 후보 성능 측정과
배포판 설치 전체 검증, 신호 재진입·자원 수명 추가 검토를 거쳐야 채택할 수 있다.

#### 신호 재진입 수명 수정

TLS destructor가 munmap한 직후 신호 처리기가 fstat을 호출하면 이전 구현은
해제한 페이지를 참조했다. guest fstat 전체와 fork 매핑 교체를 atomic busy
표시로 보호하고, destructor는 해제 전에 표시를 영구 설정한다. 재진입은
추적되는 일반 syscall로 복귀한다. 별도 시험 hook을 제품 코드에 넣지 않고,
시험 실행 파일이 munmap을 interpose해 해제 직후 SIGUSR1을 전달한다.

[2026-09-16 재현 및 짧은 확인 기록](results/fastfd-signal-2026-09-16.json):
이전 DSO에서 SIGSEGV(종료 255)를 재현했고 수정 DSO에서는 기존 fd-test와
새 신호 검사 모두 notification 비활성/스레드 활성에서 통과(hits=652)했다.
tracer 엔진은 동일하며 guest DSO와 시험만 다시 빌드했다.

작은 파일 1,000개/448,000바이트 읽기 한 쌍만 확인했다. 같은 엔진·DSO에서
fastfd 비활성 146.390ms, 활성 109.251ms(약 25.4% 시간 감소, 1.34배)였다.
순서를 무작위화하지 않은 각 1회 warm-cache 확인이므로 확정 향상률이나
전체 2배 달성 근거로 사용하지 않는다. 전체 반복 비교는 수행하지 않았다.

신호 처리기에서 최초 초기화할 때 pthread_setspecific/TLS 할당의 안전성과
siglongjmp로 호출을 탈출할 때 가속 복구는 추가 검토가 필요하다. 이 수정으로
모든 async-signal-safe 조건을 입증한 것은 아니다. Android/Gradle 변경이나
새 배포판 설치 검증은 이번 단계에 포함하지 않았다.

#### 첫 신호 호출의 초기화 비용 제한

DSO의 TLS를 initial-exec 모델로 고정하고 `-z now`로 함수 연결을 시작 시
완료한다. `readelf`에서 `R_AARCH64_TLS_TPREL`과 `BIND_NOW`를 확인했다.
런타임 dlopen용 범용 DSO가 아니라 시작 시 LD_PRELOAD하는 구성이다.

키가 32 미만일 때만 fastfd를 활성화한다. 그 이상이면 키를 삭제하고 일반
fstat 경로를 유지한다. 이는 POSIX의 pthread_setspecific 안전성 보장이 아니라
검토한 glibc NPTL 구현에 의존하는 제한이다. glibc의
[pthread_setspecific 소스](https://codebrowser.dev/glibc/glibc/nptl/pthread_setspecific.c.html)는
첫 블록을 직접 사용하고 그 밖의 블록에서 calloc을 호출한다.
[glibc 2.41의 descriptor 정의](https://github.com/bminor/glibc/blob/glibc-2.41/nptl/descr.h)는
첫 블록 크기를 32로 정의한다. 다른 libc 구현으로 확장할 때 이 전제를
다시 검증해야 하며, 임의의 pthread 함수 interposer까지 보장하지 않는다.

2026-09-16 Android MCP로 확인한 guest는 glibc 2.41이다. 새 스레드의 첫 fstat을
SIGUSR1 처리기에서 실행하는 검사와 기존 종료 직후 재진입·fork·exec 회귀가
notification 비활성/스레드 활성 모두 통과(hits=653)했다. 별도 실행에서
시험 바이너리가 constructor의 키 생성을 interpose해 32개를 먼저 예약했다.
fastfd_enabled=0, hits=0이며 파일 정보와 UID/GID 검사도 통과했다.

guest DSO SHA-256:
`b3fa5f85952f7bcd13be58c4548fdbecc08e99b07171c329baaa17480a0ce8c8`.
빌드·APK 서명·기기 설치는 통과했고 incremental 미지원은 streamed 설치로
해결했다. 이번 단계에서는 성능 비교를 하지 않았다. siglongjmp로 초기화
중간을 탈출하는 경우의 자원 수명과 가속 복구 검토는 여전히 남아 있다.

#### 비지역 탈출 중 임시 자원 수명

공유 페이지를 처음 연결하는 동안만 AArch64 rt_sigprocmask로 신호 전달을
미룬다. 임시 FD를 닫고 매핑의 TLS destructor 등록을 끝낸 뒤 이전 마스크를
복원한다. 초기 마스크 변경이 거부되면 일반 fstat으로 복귀한다. 이미 연결된
페이지를 사용하는 반복 호출에는 신호 마스크 syscall을 추가하지 않는다.

`fd-test`는 F_ADD_SEALS 직전에 SIGUSR2를 보내고 처리기에서 siglongjmp한다.
이전 b3fa5f... DSO는 16개 스레드를 순서대로 실행한 뒤 FD 수가 원래 값으로
돌아오지 않는 검사에 실패했다(종료 1). 새 DSO는 FD 수와 proot-fastfd 매핑
수가 모두 유지되고, 신호 마스크 복원과 탈출 후 일반 fstat 결과도 통과했다.
비지역 탈출로 busy 표시가 남은 스레드는 종료/exec까지 일반 경로를 쓴다.
그 특수 경우의 가속 자동 복구는 구현하지 않았으며 Linux 작업은 계속 가능하다.

2026-09-16 Android MCP에서 notification 비활성/스레드 활성 모두 전체 fd-test
통과(hits=653). memfd_create와 rt_sigprocmask를 각각 거부하는 seccomp 자식
검사도 통과했다. 같은 DSO를 bash와 dpkg에 preload한 시험에서 파이프·조기
종료·프로세스 치환 20회, 임시 패키지 생성·독립 dpkg 루트 설치·내용 비교·제거가
통과했다. 실제 배포판의 패키지 DB는 변경하지 않았다. 이 시험은 새 Debian
전체 설치를 대체하지 않는다.

DSO SHA-256:
`396be7f08f543ab3d550555a9e35e0b783dda1deaf8a6d767c29f21f9020cc38`.
guest 빌드·APK 서명·설치 통과, incremental 미지원은 streamed 설치로 해결했다.
이번 단계는 성능 비교·Android Gradle 빌드를 반복하지 않았다. 다음 검증은
누적 가속 후보의 실제 배포판 설치와 대표 작업 성능이다.

### 공유 자격 정보 후보의 새 Debian 설치 검증

설치 명령의 내부 실험 옵션 `fastfdLibrary`는 지정한 DSO 파일 하나만 guest의
`/usr/local/lib/terminal-faststat.so`로 bind한다. host에는 PROOT_FASTSTAT=2와
PROOT_FASTFD=1을, guest에는 LD_PRELOAD와 PROOT_FASTFD=1을 전달한다.
host Android 프로세스에는 glibc LD_PRELOAD를 설정하지 않는다. 기본값은 null이며
일반 설치·세션은 바뀌지 않는다. 단위 시험은 이 환경과 bind의 적용 범위를 검사한다.

독립 runtime 시험 APK의 `RootlessFreshInstallTest`에 다음 옵션을 추가했다.
`rootlessFastfd=true`와 `rootlessFastfdSha256=<DSO SHA-256>`를 기존 stat/path/
mutation/fstat 옵션 및 엔진 SHA-256과 함께 지정한다. 시험 APK에만
`lib/arm64-v8a/libproot.so`를 후보로 교체하고 `libproot-faststat.so`를 넣어 다시
서명한다. 제품 APK의 기본 엔진이나 기존 사용자 rootfs는 교체하지 않는다.

2026-09-16 엔진 a25b410f...와 DSO
`119b88d643e6c413843cd0286601b5679018e7ab567612ea8dcf7f677c95ace2`로
새 Debian 설치·기본 패키지/환경 확인·하드링크·가속 fstat 호출 확인이
92.070초에 통과했다. notification과 kernel-pipe 옵션은 끈 조합이다.
시험 후 fresh-rootless 임시 디렉터리 수는 0이었다. 전체 설치 시간은 네트워크와
다운로드를 포함하므로 이전 설치 시간과의 성능 비교로 사용하지 않는다.

처음에는 호출 인자의 DSO 해시를 잘못 전달해 검증 전에 중단됐다. 이를 고친
첫 설치는 완료됐지만 종료 진단을 받지 못해 가속 확인 assertion이 실패했다.
coreutils의 stderr 종료 이후에도 진단을 받을 수 있도록, opt-in 진단에
`PROOT_FASTSTAT_REPORT_FD`(3..1023, 기본 stderr)를 추가했다. 시험에서는
`exec 3>&2` 후 report FD=3을 자식에 전달한다. 별도 cat 검사에서 fd_hits=1을
확인한 뒤 설치 시험을 재실행해 통과했다. 이 진단은 성능 비교에서는 끈다.

JDK 17 정규 assembleDebug/런타임 단위 시험/image 시험과 instrumentation APK
빌드가 통과했다. Gradle 캐시 read-only 오류는 기존 캐시 쓰기 권한으로 해결했고,
Android MCP incremental 설치 미지원은 streamed로 해결했다. UI 변경이 없어
replicant, 외부 API/앱 변경이 없어 context7/codex_apps는 사용하지 않았다.

후보 전체 비교는 [80개 결과](results/fastfd-candidate-2026-09-16.json)에 저장했다.
5쌍 교대 중앙값에서 작은 파일 읽기는 183.543→103.685ms(1.77배), 메타데이터는
67.010→52.188ms(1.28배), tar는 898.920→743.690ms(1.21배)였다. 순차 I/O는
개선되지 않았다. 경로 DSO를 함께 켠 후보의 메타데이터 수치는 이전 진입 가속
단독 결과보다 나쁘므로, fastfd와 경로 DSO의 분리 검증이 다음 과제다. 이전
2.61배 메타데이터 결과를 이번 누적 후보의 성능으로 인용하면 안 된다.

### 경로 조회 DSO와 FD 가속 분리

`PROOT_FASTSTAT_PATH=0`은 guest DSO의 stat/lstat/fstatat 경로를 일반 syscall로
돌린다. 이 syscall에는 tracer의 STAT_ENTRY/STAT_KERNEL 가속이 계속 적용된다.
전용 syscall PC 초기화와 PROOT_FASTFD는 유지한다. 옵션 미지정 시 기존 경로
DSO 동작을 유지한다. 내부 설치 실험의 fastfdLibrary 지정 시에는 이 옵션을
함께 전달하도록 변경했다. 제품 기본 엔진/일반 설치는 바꾸지 않았다.

`proot_faststat_enabled`는 여전히 DSO 초기화 여부이고 새 진단 함수
`proot_faststat_path_enabled`는 경로 DSO 활성 여부다. 경로 시험 driver는 후자를
사용한다. 경로 off 회귀 hits=0/fallbacks=842, 동시에 실행한 fd 회귀 hits=653,
경로 on 회귀 hits=831/fallbacks=11이 Android MCP에서 통과했다.

[3쌍 교대 분리 시험](results/fastfd-path-split-2026-09-16.json)은 같은 엔진과
DSO에서 이 옵션만 바꿨다. 메타데이터 중앙값은 on 51.778ms → off 25.851ms,
작은 파일 읽기는 108.564ms → 106.502ms였다. 경로 DSO의 추가 처리 때문에
메타데이터가 느려졌음을 뒷받침하며 FD 가속은 유지된다. 이 두 배 차이는
경로 DSO on/off 사이의 결과이며 제품 전체 2배 달성 수치가 아니다.

엔진 a25b410f... / DSO
`93aa3f7d521dac15bb79138928d6a889bcc8673258b58aed5b6a0d5715c2f5ba`의
FD 전용 설치 구성으로 새 Debian 설치·환경·하드링크·실제 가속 fstat 검사가
89.669초에 통과했다. JDK 17 정규 빌드·단위 시험·image 시험 및 instrumentation
빌드도 통과했다. APK incremental 미지원은 streamed 설치로 해결했다.
전체 8개 작업 비교는 반복하지 않았다. 나머지 작업의 이 구성 성능과 open/close
비용 개선은 후속 검증 대상이다.

## 공유 경로 규칙 게시 기반

host의 `PROOT_FASTPATH=1`은 fastfd 공유 영역에 bind 규칙을 게시한다.
실험 ABI는 버전 2, 매핑 크기는 32 KiB로 변경했다. tracer와 guest DSO를
함께 다시 빌드해야 한다. 최대 32개 bind와 16 KiB 경로 문자열을 담으며,
한도를 넘으면 공유 경로 조회를 비활성화한다. 파일 존재 여부·inode·내용은
저장하지 않는다. 규칙 변경 전에 모든 연결된 페이지를 무효화하고 각 tracee의
재개 직전에 해당 namespace의 규칙을 게시한다. fork 후 공유되는 binding
목록도 고려해 무효화 범위를 개별 FileSystemNameSpace로 제한하지 않았다.

25단계의 소비자는 시험용 `proot_fastpath_translate`뿐이었다. 절대 경로 중 정규화가
필요하지 않은 경로만 번역하며 중첩 bind에 걸리면 기존 변환으로 복귀한다.
이 진단 함수는 심볼릭 링크나 파일 존재 여부를 검증하지 않는다. 이후 실제 open
연결은 아래 단계에서 추가했다. 규칙 게시 단계 자체의 파일 I/O 개선 수치는 없다.

`path-rules-test.py`는 기존 RESOLVE 결과와 공유 조회를 비교한다. bind 추가·제거,
다른 스레드 재개 후 갱신, fork/chroot 후 부모의 규칙 유지, 잘못된 경로·출력
버퍼를 검사한다. 시험 중 기존 chroot 처리의 초기화하지 않은 path_guest 사용을
발견해 수정했고 realpath 실패도 반환하도록 했다. 존재하지 않는 chroot 대상의
ENOENT와 유효한 대상의 정상 동작을 검사한다.

2026-09-16 Android MCP의 기존 Debian/app UID 시험에서 위 시험과 fd-test
(hits=653)가 통과했다. 엔진 SHA-256은
`ae78f676206eaef60eb155bc25b4a02c11e8bde8b128d7861c6b2e7481a99329`,
DSO는 `991793c1593db8c0dfea83e746e10338f2c99bd23b7c59c619cfcc34335bd1f1`이다.
ARM64 엔진/DSO 빌드와 APK 서명·설치를 통과했다. incremental 설치 미지원은
streamed 설치로 해결했다. 실험 native 코드만 변경하여 Gradle과 새 Debian 설치,
전체 성능 비교는 반복하지 않았다. UI·새 외부 API·외부 앱 변경이 없어
replicant/context7/codex_apps는 사용하지 않았다.

## 읽기 전용 open 연결

host `PROOT_FASTPATH=1`, guest `PROOT_FASTOPEN=1`을 기존 fastfd 구성에 추가한다.
`open/open64/openat/openat64`에서 정규화된 절대 경로의 읽기 전용 열기를
처리한다. 처음에는 root binding에 한정했고 아래 단계에서 최상위 bind도
지원했다. 상대 경로, 중첩 bind, 링크, 쓰기/생성, O_NOFOLLOW,
지원하지 않는 플래그는 libc openat로 복귀한다. glibc 내부 비공개 호출과 정적
ELF는 이 interposition의 적용 대상이 아니다. 제품 기본 구성은 변경하지 않았다.

공유 규칙으로 host 경로를 만들고 openat2(NO_SYMLINKS, O_PATH)로 대상을 고정한다.
fstat로 일반 파일·디렉터리임을 확인한 뒤 /proc/self/fd를 통해 원래 플래그의
openat로 다시 연다. 따라서 경로가 FIFO·장치로 바뀌어도 잘못된 대상을 열지
않는다. syscall 이후 규칙 버전을 재확인하며 변경 또는 오류 시 임시 FD를 닫고
기존 처리로 복귀한다. 파일 내용·inode 결과는 캐시하지 않는다.

전용 PC의 openat도 PRoot 추적 생략 목록에 추가했다. 실제 마지막 열기는 원래
syscall 번호인 openat이므로 해당 번호를 거부하는 guest seccomp도 적용된다.
임시 FD가 살아 있는 동안 신호를 미루며, libc fallback을 통해 blocking FIFO의
스레드 취소 동작을 유지한다. 이 내부 PC 예외는 보안 격리 장치가 아니다.

Android MCP에서 fastopen-test.py의 내용 변경·rename·링크·FIFO·flags·EFAULT·
8스레드·fork·openat 거부/openat2 거부 복귀 검사가 통과했다(open hits=438).
기존 fd-test(hits=653), 공유 규칙 bind/chroot 검사, open-test의 FD 전달 및
blocking FIFO 취소 검사도 통과했다. ARM64 엔진/DSO 빌드와 APK 서명·설치 통과,
incremental 설치 미지원은 streamed 설치로 해결했다. 엔진은
`58eea29df2278fe33fc3ec0e594ca5859a48e6bcda1bd1503f62e6cab1847a81`,
DSO는 `f445899cc8622c87a7ac14cf16455255b5f8fcdd8b4e9b182803a36e0c4f33e1`이다.

작은 파일 1,000개/448,000 bytes 읽기만 off/on 각 1회 비교했다. 동일한 fastfd/
공유 규칙 구성에서 106.093→86.956ms(약 18% 시간 감소)였다. guest CPU는
53.287→57.848ms로 늘었다. 이는 개선 방향 확인용이며 반복 측정에 의한 확정
성능이나 원본 대비 2배 달성 증거가 아니다. 전체 비교와 새 Debian 설치는 아직
수행하지 않았다. 실험 native 코드만 변경해 Gradle은 반복하지 않았다.

### 새 설치 시험의 fastOpen 옵션

내부 설치 실행기에 `fastOpen` 옵션을 추가했다. DSO 지정이 필수이며 알림 방식과
동시 사용은 거부한다. host에는 PROOT_FASTPATH=1, guest에는 PROOT_FASTOPEN=1을
추가한다. 기본값은 false다. 단위 시험으로 host/guest 옵션 분리, 명시적 활성화,
DSO 없는 구성과 notification 병용 거부를 검사한다.

`RootlessFreshInstallTest`의 기존 fastfd 시험 인자에 `rootlessFastOpen=true`를
추가하면 설치·구성·검증 전체에 적용한다. 종료 진단에 open_hits를 추가했으며,
설치 후 `/etc/debian_version` 읽기 등을 수행한 출력에서 양수 open_hits와 fd_hits를
각각 요구한다. 바이너리와 DSO SHA-256 검증도 기존대로 수행한다.

2026-09-17 엔진 58eea29d...와 진단 추가 DSO
`ff29841795d50544412a88ac04abd58ad67f20f1f9882fdbdec765cbd2e39b70`로
Android MCP 새 Debian 설치 시험이 88.558초에 통과했다. 설치·환경·하드링크와
실제 가속 open/fstat 사용 확인을 포함한다. 이 시간은 다운로드를 포함하므로
성능 비교로 사용하지 않는다. 시험 후 임시 fresh-rootless 디렉터리는 0개였다.
JDK 17에서 정규 assembleDebug/runtime 단위 시험/image 시험과 instrumentation
APK 빌드가 통과했다(170 tasks, 15 executed). DSO 빌드도 통과했다. APK의
incremental 설치 미지원은 streamed 설치로 해결했다. UI·새 외부 API·외부 앱
변경이 없어 replicant/context7/codex_apps는 사용하지 않았다.

### 최상위 bind 파일 열기

`/workspace`, `/root`처럼 게스트 최상위 디렉터리에 연결된 bind도 공유 규칙으로
열 수 있다. 기존 중첩 bind의 경로 정규화 제한은 유지한다. O_PATH로 고정한
대상에 fstatfs를 수행하고 ext4/F2FS/tmpfs/XFS/Btrfs/overlay/EROFS/squashfs에
속하는 일반 파일·디렉터리만 다시 연다. 파일시스템 정보는 캐시하지 않는다.
procfs/sysfs 및 목록에 없는 파일시스템은 기존 libc/PRoot 처리로 복귀한다.
따라서 `/proc`를 다른 게스트 이름으로 bind해도 auxv 처리를 우회하지 않는다.
FUSE 저장소는 아직 가속하지 않는다. fstatfs가 거부돼도 기존 open으로 복귀한다.

2026-09-17 Android MCP에서 최상위 bind의 가속 사용, umount 뒤 ENOENT,
다른 이름에 bind한 procfs auxv의 원래 syscall 결과 일치와 가속 미사용을
검사했다. fstatfs seccomp 거부 복귀를 기존 fastopen 회귀에 추가해 통과했다
(open hits=439). FD 회귀(hits=653)와 전용 syscall PC 회귀도 통과했다.
DSO 빌드·시험 APK 서명/설치 통과; incremental 설치 미지원은 streamed로 해결했다.
DSO SHA-256은 `4a18d83318697862a137710a1a1615446b175c261a9d433cbc13d93c151b234f`다.
엔진은 58eea29d... 그대로다. 실제 파일시스템 시험 대상은 ext4이며 나머지 허용
유형은 이번 기기 시험에서 별도로 검증하지 않았다. 성능 비교·새 설치·Gradle은
반복하지 않았다. 변경 범위는 실험 DSO와 기능 시험이다. UI·외부 API·외부 앱
변경이 없어 다른 MCP는 사용하지 않았다.

### 누적 후보 전체 비교

2026-09-17 [3쌍 교대 비교](results/fastopen-candidate-2026-09-17.json)에서 제품 원본
대비 메타데이터 65.925→30.777ms(2.14배), 작은 파일 읽기
183.911→86.390ms(2.13배)였다. 생성·rename·삭제는 1.25배, Python import는
1.16배, tar 추출은 1.29배였으며 순차 읽기/쓰기 및 fsync 쓰기는 개선이 없었다.
현재 후보의 전체 2배 달성을 주장하지 않는다. API35 ARM64 redroid/ext4의
warm-cache 결과이며 실제 휴대폰/F2FS 검증을 대신하지 않는다. 다음 병목은
현재 기존 처리로 복귀하는 생성·쓰기 경로다. 자세한 표와 검증 범위는
[29단계 기록](../../../docs/ROOTLESS_2X_PROGRESS.md)에 있다.

## 새 일반 파일 생성 실험

guest `PROOT_FASTCREATE=1`을 fastopen 구성에 추가하면 쓰기/읽기쓰기 O_CREAT의
새 파일 생성을 가속한다. 현재 기본값은 off이며 설치 실행기의 fastOpen 옵션만으로
활성화되지 않는다. 정규화된 절대 경로와 기존 fastopen의 저장소/bind 제한을 따른다.
일반 mode 비트(0777)만 처리하고 특수 비트·추가 flags·상대 경로는 기존 처리다.

부모 디렉터리를 O_PATH/NO_SYMLINKS로 고정하고 파일시스템과 공유 규칙을 확인한
뒤, 원래 flags에 O_EXCL을 더해 openat을 실행한다. 이미 파일이 있으면 기존
libc open으로 돌아가므로 기존 파일 truncate, symlink 및 권한 보완 처리를
유지한다. 생성 성공 뒤에는 namespace가 바뀌어도 재시도하지 않는다. 부모 조회와
실제 실행 사이의 동시 rename/bind에 대해서는 tracer의 경로 조회 후 실행과 같은
모델이며, 전체 namespace 변경을 직렬화하는 트랜잭션은 아니다. umask는 커널에
맡기며 파일 정보나 존재 여부를 캐시하지 않는다.

2026-09-17 Android MCP에서 새 생성의 가속 호출, mode/umask, 기존 파일 truncate,
링크 append, 8스레드 O_EXCL 경합의 단일 성공, seccomp openat 거부가 통과했다.
기존 fastopen 회귀(bind/umount/procfs/FD/오류/스레드/seccomp)도 통과했다.
DSO 빌드 및 시험 APK 서명·설치 통과; incremental 미지원은 streamed로 해결했다.
DSO 해시는 `4147b3fd803669801ffb9aee10e57461e1904e2fbea21c0068be28eb6c826af4`,
엔진은 58eea29d...다. 이 생성 옵션으로 새 Debian 설치는 아직 검증하지 않았다.

동일 후보에서 생성 옵션 off/on 각 1회만 확인했다. 생성·rename·삭제 1,000회는
297.021→279.954ms, tar 추출은 706.177→697.407ms였다. guest CPU는 각각
159.425→172.776ms, 347.802→374.168ms로 증가했다. 단일 비교의 방향 확인이며
확정 성능 또는 원본 대비 배속으로 사용하지 않는다. 전체 비교·Gradle·새 설치는
반복하지 않았다. 실험 DSO 변경으로 UI/외부 API/외부 앱 MCP는 필요하지 않았다.

### 생성 가속의 새 설치 시험 연결

내부 설치 실행기에 기본 off인 `fastCreate`를 추가했다. fastOpen을 요구하고,
guest에만 PROOT_FASTCREATE=1을 전달한다. 단위 시험은 의존 조건과 기본 비활성
동작을 확인한다. instrumentation 인자 `rootlessFastCreate=true`로 설치·설정·검증
전 단계에서 켤 수 있다. 설치 후 Python으로 파일을 생성하고 종료 진단의
create_hits가 양수임을 요구한다. 기존 open/fstat 양수 확인도 유지한다.

2026-09-17 Android MCP의 새 Debian 설치·환경·하드링크·실제 create/open/fstat
가속 확인이 86.911초에 통과했다. 시험 후 임시 설치 디렉터리는 0개였다.
엔진 58eea29d...와 진단 추가 DSO
`f14710f4d7bd2bbfbc9292f7f725f9d7e025ed867c4bee227635c77da6c315b5`를 사용했다.
JDK 17 정규 빌드·runtime 단위 시험·image 시험·instrumentation APK 빌드가
통과했다(170 tasks, 15 executed). DSO 빌드도 통과했다. incremental 설치
미지원은 streamed로 해결했다. UI/외부 API/외부 앱 MCP는 필요하지 않았다.
설치 시간은 네트워크를 포함하므로 성능 비교에 사용하지 않는다.

## 게스트 내부 unlink 실험

guest `PROOT_FASTUNLINK=1`은 fastopen 구성에서 unlink/unlinkat(flags=0)을 연결한다.
정규화된 절대 경로의 부모 디렉터리를 O_PATH/NO_SYMLINKS로 고정하고 일반 저장소와
공유 규칙 버전을 확인한 뒤 전용 PC의 unlinkat을 실행한다. 성공 후 재실행하지
않는다. 실패는 기존 PRoot 경로로 복귀해 오류 및 fake-root 처리를 유지한다.
상대 경로·중첩 bind·rmdir·지원하지 않는 파일시스템은 기존 경로다. 최종 심볼릭
링크는 따라가지 않고 링크 자체를 삭제한다. 일반 libc 내부 호출이나 정적 ELF까지
모두 연결하는 구현은 아니다. 엔진과 DSO를 함께 다시 빌드해야 한다.

2026-09-17 Android MCP에서 열린 inode/하드링크/dirfd/링크/디렉터리 fallback/
errno/guest seccomp와 실제 가속 사용 확인이 통과했다. 생성·읽기 회귀도 함께
통과했다. 파일 생성·삭제 100회에서 옵션 off/on 계측은 enter_stop=610→510,
exit_stop=141→141, read_string=358→258, vm_write=452→352였다. 앞서 기각한
tracer 내부 unlink와 달리 진입 100회가 제거됐다. 시간 벤치마크는 실행하지 않았다.

ARM64 엔진/DSO 빌드·APK 서명·설치 통과. incremental 미지원은 streamed로 해결했다.
엔진 SHA-256은 `8995a580853089ef5452ea24969f6caa585ff55c42bb975512123228dfa1c170`,
DSO는 `0fc52429093460dcebca25599bea7307b2acb775295ea493cbb702e20b8f2e5c`다.
삭제 옵션은 아직 설치 실행기에 연결하지 않았고 새 Debian 설치 검증도 남아 있다.
실험 native 코드만 변경해 Gradle은 반복하지 않았다. UI·외부 API·외부 앱 MCP는
필요하지 않았다. 제품 기본 엔진과 일반 세션 구성은 변경하지 않았다.

## 가상 작업 디렉터리와 상대 경로

공유 ABI 버전 3은 가상 cwd를 추가한다. 매핑 크기는 32 KiB 그대로이며 엔진과
DSO를 함께 다시 빌드해야 한다. chdir/fchdir 및 PRoot가 cwd를 갱신하는 rename
처리에서 연결된 규칙을 먼저 무효화하고 tracee 재개 전에 새 cwd/bind를 게시한다.
fork의 독립 namespace는 독립 페이지를 사용하며, 스레드가 공유하는 cwd 변경은
다른 스레드의 페이지도 무효화한다.

open/create/unlink에서 AT_FDCWD의 단순 상대 경로를 같은 snapshot의 cwd와
결합한다. 실제 디렉터리 FD를 지정한 상대 경로, `.`/`..` 정규화가 필요한 경로,
중첩 bind 등은 기존 처리다. 커널 cwd를 PRoot cwd로 간주하지 않는다. 기존의
읽기 후 버전 재확인과 생성/삭제 전 버전 확인을 유지한다. 이는 기존 PRoot의
가상 cwd를 공유하는 구현이며 외부 프로세스에 의한 모든 rename을 추적하는
커널 cwd 구현을 새로 제공하는 것은 아니다.

2026-09-17 Android MCP에서 cwd-test.py의 상대 open/create/unlink 가속 사용,
chdir/fchdir, 다른 스레드의 cwd 변경, 실패한 chdir의 상태 유지, 실제 dirfd의
fallback, cwd rename, fork 이후 부모/자식 분리가 통과했다. bind/chroot 공유 규칙,
기존 생성·읽기·삭제 및 FD 회귀(hits=653)도 통과했다. ARM64 엔진/DSO 빌드·시험
APK 서명·설치 통과; incremental 설치 미지원은 streamed로 해결했다.
엔진 해시는 `47d3b22ed4dc2bb48e2146ff49f5b694bea16494e228c4c652073d970d98245d`,
DSO는 `16abfaca639a912959c799ec0e32cab1720b44431244acf3c2884e05e5657e73`다.
시간 벤치마크·새 설치·Gradle은 반복하지 않았다. 새 ABI 후보의 전체 설치 검증은
남아 있다. 실험 native 코드 범위라 UI/외부 API/외부 앱 MCP는 사용하지 않았다.

### 삭제·상대 경로 후보의 새 설치 시험 연결

내부 설치 실행기에 기본 off인 fastUnlink를 추가했다. fastOpen을 요구하고 guest에
PROOT_FASTUNLINK=1을 전달한다. instrumentation의 rootlessFastUnlink=true로 전체
설치 단계에 적용한다. 설치 후 Python이 chdir한 디렉터리에서 상대 경로로 생성·
내용 확인·삭제를 수행하며, 종료 진단의 unlink_hits 양수를 기존 create/open/fstat
확인과 함께 요구한다. 단위 시험은 옵션 의존 조건과 기본 비활성을 확인한다.

2026-09-17 Android MCP에서 ABI 3/상대 경로/삭제 가속 누적 후보의 새 Debian 설치,
기본 환경·하드링크·상대 파일 내용·가속 create/open/unlink/fstat 확인이
88.025초에 통과했다. 시험 후 임시 설치 디렉터리는 0개였다. 엔진은 47d3b22e...,
진단 추가 DSO는 `b6cbfc60a530f6d855f33babbb78588c63672050380e1342300fef7bf05b4d37`다.
JDK 17 정규 빌드·runtime 단위 시험·image 시험·instrumentation APK 빌드와 DSO
빌드가 통과했다. incremental 설치 미지원은 streamed로 해결했다. 성능 비교는
반복하지 않았고 설치 시간을 성능 향상 수치로 사용하지 않는다. UI/외부 API/
외부 앱 MCP는 필요하지 않았다. 일반 세션과 제품 기본 엔진은 아직 변경하지 않았다.

### dot와 중복 slash 표기

open/create/unlink는 공유 cwd와 결합한 경로에서 중복 `/`와 `.` 요소를 정리한다.
끝의 `/` 또는 `/.`는 디렉터리 요구 조건을 남기므로 일반 파일을 잘못 열지 않는다.
`..` 요소는 정리하지 않고 기존 처리로 복귀한다. 실제 경로의 심볼릭 링크 제한은
기존 커널 NO_SYMLINKS 검사로 유지한다. 진단 전용 proot_fastpath_translate의
입력 허용 범위는 바꾸지 않았다.

2026-09-17 Android MCP에서 `./file`, `././file`, 중복 slash 열기의 가속 사용과
`././new` 생성·`./new` 삭제의 가속 사용을 확인했다. `file/.`, `file//.`, `file/`는
ENOTDIR를 유지했다. cwd·생성·읽기·삭제 회귀도 통과했다. DSO 빌드·시험 APK
서명/설치 통과; incremental 미지원은 streamed로 해결했다.
DSO 해시는 `2a37aa03e6a89c0472ed821bef167dc458ff0340c26125917f24d1ee63cb6c80`이고
엔진은 47d3b22e... 그대로다. 성능 비교·새 설치·Gradle은 반복하지 않았다.
실험 DSO 변경으로 다른 MCP는 필요하지 않았다. 전체 성능 개선의 추가 배속을
주장하지 않으며, 새 설치 검증은 직전 DSO 후보에 대한 결과다.

## syscall별 계측과 TCGETS 직접 전달

host `PROOT_PERF=1 PROOT_PERF_SYSCALLS=1`은 실험 엔진에서 syscall 이름별 진입·
반환 횟수를 출력한다. 경로나 파일 내용은 기록하지 않는다. PRoot의 chained
호출과 알 수 없는 번호는 void에 모은다. 각 이름의 합계가 기존 enter/exit 총계와
일치하는지 검사했다. 이 진단은 일반 성능 비교에서는 끈다.

생성·삭제와 tar 추출 전체 실행에서 ioctl 왕복이 각각 2,066회와 4,072회였다.
소스상 TCGETS는 변경 없이 전달되므로 host `PROOT_TCGETS_DIRECT=1`을 추가했다.
ARM64 ioctl의 요청 값이 정확히 TCGETS(0x5401)일 때만 seccomp ALLOW로 전달한다.
TCGETS2/TCSETS 계열 변환, SIOCGIFINDEX, FICLONE 및 다른 ioctl은 기존 추적을
유지한다. 추가 guest/host seccomp 정책은 계속 적용된다. 기본값은 off다.

2026-09-17 Android MCP에서 옵션 off/on 모두 PTY의 isatty, 일반 파일/파이프,
EBADF/EFAULT, termios 설정·복원·조회, guest seccomp EPERM 검사가 통과했다.
전체 작업 계측의 ioctl은 생성·삭제 2,066→7회, tar 4,072→7회로 줄었다. 총 반환
정지는 각각 2,176→119회, 7,329→3,266회였다. 측정은 시작·준비·검증·정리도
포함한 전체 실행이며 시간 측정 구간만의 비용이나 속도 배속을 뜻하지 않는다.
[원래 진단](results/syscall-profile-2026-09-17.json),
[직접 전달 진단](results/tcgets-profile-2026-09-17.json).

ARM64 엔진 빌드·시험 APK 서명/설치 통과. 최초 패치 조립은 기존 syscall.c 항목
중복 검사에서 실패해 기존 항목을 갱신하도록 고친 뒤 최종 엔진을 빌드했다.
incremental 설치 미지원은 streamed로 해결했다. 최종 엔진 해시는
`eb6d4105348b26aa5a8fc48937ed13449433676892586f07eb65fa30550e62be`다.
DSO는 dot 경로 후보 2a37aa03... 그대로다. 새 옵션의 Debian 설치·최종 시간 비교는
남아 있다. Gradle/UI 및 외부 API/앱 MCP는 이번 실험 범위에 없어 사용하지 않았다.

### 생성·삭제·cwd·TCGETS 누적 후보 비교

2026-09-17 [3쌍 교대 비교](results/cwd-tcgets-candidate-2026-09-17.json)의 원본 대비
중앙값은 작은 파일 읽기 182.418→57.842ms(3.15배), 메타데이터
66.787→30.810ms(2.17배), 생성·rename·삭제 366.816→234.282ms(1.57배)였다.
Python import는 1.22배, tar는 1.33배였고 순차 읽기·쓰기·fsync 쓰기는 개선이
없었다. 모든 작업의 내용 검증이 통과했다. 조건과 표본 범위는 원시 결과에 있다.
여러 개선을 묶은 결과이므로 단일 옵션의 효과로 분리해 해석하지 않는다.
TCGETS를 포함한 새 설치는 아직 검증하지 않았으며 제품 기본 적용도 남아 있다.

## ID 조회의 반환 정지 제거

host `PROOT_ID_ENTRY=1`은 ARM64 getuid/geteuid/getgid/getegid를 진입 시점에
fake_id0의 현재 real/effective ID로 완료한다. ID를 별도로 캐시하지 않으며
getresuid/getresgid 및 ID 변경 호출은 기존 경로를 사용한다. fake_id0가 없거나
지원하지 않는 확장이 있으면 반환 추적을 유지한다. 기본값은 off다.

2026-09-17 Android MCP의 Debian/app UID 시험에서 `id-entry-test.py`가 off/on
모두 통과했다. real/effective/saved ID 변경, 8개 스레드, fork 격리, 네 조회
syscall에 대한 guest seccomp EPERM을 검사했다. 누적 가속과 함께 실행한 FD
회귀 시험도 통과했다(hits=653). ARM64 엔진 빌드와 Python 구문 검사가 통과했다.

Python에서 geteuid를 1,000회 호출한 전체 실행의 해당 syscall 진입은 양쪽 모두
1,001회였고 반환 정지는 1,001→0회였다. 전체 enter_stop은 1,198회 그대로,
exit_stop은 1,070→66회였다. 이는 추적 왕복 감소의 증거이며 시간 배속은 아니다.
엔진 SHA-256은
`0677e4c247d2f830d5318f6a29a10707521c8573a5657a86f5ba7acca412ea3a`다.
DSO는 2a37aa03... 그대로다. 전체 성능 비교와 새 Debian 설치는 반복하지 않았다.
실험 네이티브 패치 범위이므로 Gradle/UI/외부 API·앱 MCP는 사용하지 않았다.
새 옵션의 설치 통합 검증과 제품 기본 적용은 남아 있다.

### ID·TCGETS 누적 후보의 새 Debian 설치

설치 실행기에 기본 비활성 `tcgetsDirect`와 `idEntry`를 추가했다. 시험 인자
`rootlessTcgetsDirect=true rootlessIdEntry=true`로 켜며 검증된 실험 엔진 해시가
필요하다. 두 값은 host 환경에만 전달한다. 단위 시험에서 각각/동시 활성화와
기본 비활성을 확인했다.

2026-09-17 Android MCP의 API35 ARM64 redroid/ext4에서 최신 ID 엔진 0677e4c2...
및 dot 경로 DSO 2a37aa03...를 사용한 새 Debian 설치가 85.842초에 통과했다.
stat/path/mutation/fstat 및 fastfd/open/create/unlink와 새 두 옵션을 함께 켰다.
공식 패키지 설치·기본 환경 검증·하드링크·상대 파일 내용 검증과 양수인
create/open/unlink/fstat 가속 카운터를 확인했다. 설치 시간은 배속 증거가 아니다.

JDK 17에서 정규 assembleDebug/runtime 단위 시험/image 시험 및
assembleDebugAndroidTest가 통과했다(170 tasks). APK 서명 검증·설치가 통과했고
incremental 설치 미지원은 streamed 설치로 해결했다. 전체 성능 비교는 반복하지
않았다. UI/외부 API/외부 앱 변경이 없어 다른 MCP는 사용하지 않았다.
일반 사용자 세션과 제품 기본 엔진 적용, 전체 작업 2배 달성은 여전히 남아 있다.

## 일반 파일 권한·소유자 변경의 진입 완료 실험

host `PROOT_PERMISSION_ENTRY=1`은 ARM64 fchmodat/fchownat를 경로 번역과 확장
진입 처리 후 tracer에서 실행한다. fake_id0의 effective UID가 0이고 허용한
확장만 있을 때 일반 파일을 대상으로 한다. USERLAND 메타파일 모드, 디렉터리,
특수 경로, 지원하지 않는 flags 등은 기존 반환 처리를 유지한다. fchownat는
fake_id0가 변환한 UID/GID를 사용하고 EPERM/EACCES 처리는 기존 fake-root와
맞춘다. 실행 후 오류도 최종 결과로 반환하며 변경 작업을 재시도하지 않는다.
기본값은 off이고 제품에는 적용하지 않았다.

2026-09-17 Android MCP에서 `permission-entry-test.py`가 off/on 모두 통과했다.
권한 비트(setuid 포함), 소유자 변경, 파일 내용, 심볼릭 링크/디렉터리, ENOENT,
EFAULT, 잘못된 flags와 guest seccomp EPERM 및 거부 후 권한 불변을 검사했다.
fchmodat와 fchownat 각각 100회 실행 시 각각의 반환 정지가 100→0회로 줄었다.
전체 enter_stop은 719회 그대로, exit_stop은 311→111회였다. 반면 read_string은
528→728회로 늘었다. 실제 시간 이득은 아직 입증하지 않았으며 이 경로를
채택할지는 후속 누적 비교로 판단한다.

ARM64 엔진 빌드, Python 구문 검사, 시험 APK 설치가 통과했다. incremental
미지원은 streamed 설치로 해결했다. 엔진 SHA-256:
`cbb2d40b7c032bca127c0453b4493471e55c37a16f7e89f4e89482d52d218115`.
전체 벤치마크·새 Debian 설치·Gradle은 반복하지 않았다. 실험 네이티브 패치여서
외부 API·UI·외부 앱 MCP는 필요하지 않았다. 새 옵션의 설치 통합은 남아 있다.

### 권한 변경 경로의 재독 제거

권한 변경 진입 완료는 이제 같은 syscall에서 번역한 경로를 지역 버퍼로 전달한다.
확장 처리 후 경로 포인터가 달라지면 가속을 적용하지 않는다. syscall 사이의
경로 캐시는 없으며 빈 경로는 기존 AT_EMPTY_PATH/dirfd 처리를 유지한다.

2026-09-17 Android MCP에서 기존 권한 시험에 dirfd 상대 경로·빈 경로를 추가해
off/on 모두 통과했다. 동일한 chmod/chown 각 100회 진단에서 enter_stop 719,
read_string 528은 양쪽이 같았고 exit_stop은 311→111회였다. 직전 실험의 추가
read_string 200회가 제거됐으며 각 syscall의 exit는 100→0회를 유지했다.
실제 시간 이득은 아직 측정하지 않았다.

엔진 빌드·Python 구문 검사·시험 APK 설치가 통과했다. 새 엔진 SHA-256은
`cdd9cb06bbc687a2b0fa79b65ff5d786d321a38f701e4ab765eecdbeab5579dc`다.
incremental 설치 미지원은 streamed로 해결했다. 전체 비교·새 설치·Gradle은
반복하지 않았다. 제품 기본 적용과 권한 옵션 설치 통합 검증은 남아 있다.

### ID·권한 옵션의 tar 한정 비교

2026-09-17 동일 최신 엔진에서 두 옵션 off / ID만 on / 둘 다 on을 순환 순서로
각 3회 실행했다. 1,000개 파일의 Python tar 추출이며 진단은 끄고 기존 누적
가속은 동일하게 유지했다. 9개 실행의 파일 내용 검사와 종료 코드가 모두 통과했다.
[조건·해시·원시 표본](results/permission-id-tar-2026-09-17.json).

| 옵션 | 벽시계 중앙값 ms | guest CPU 중앙값 ms |
| --- | ---: | ---: |
| 둘 다 off | 679.612 | 373.657 |
| ID만 on | 660.439 | 362.721 |
| ID·권한 on | 656.542 | 340.239 |

ID만 켠 결과의 시간 감소는 약 2.8%, 권한 옵션의 추가 감소는 약 0.6%였다.
표본 범위가 겹치므로 권한 옵션의 실질적 벽시계 이득은 입증하지 못했다.
guest CPU에는 tracer CPU가 포함되지 않아 전체 CPU 감소로 해석할 수 없다.
권한 옵션은 실험 상태로 남기며 이 결과로 제품 기본 적용을 정당화하지 않는다.
이 시험은 원본 엔진 대비 배속 비교가 아니며 이전 원본 측정과 합쳐 배속을
계산하지 않는다. Android MCP를 사용했고 코드 변경·새 설치·전체 비교는 없었다.

## 공유 경로 기반 stat

guest `PROOT_FASTSTAT_SHARED=1`은 fastfd 공유 페이지의 cwd/bind 정보를 사용해
stat/lstat/fstatat의 단순 경로를 처리한다. host FASTPATH 및 guest FASTFD 설정이
필요하며 기본값은 off다. `PROOT_FASTSTAT_PATH=0`과 함께 사용할 수 있다.
기존 resolve prctl을 요청하지 않는다. NO_SYMLINKS로 O_PATH FD를 얻고 보통
저장장치인지 확인한 뒤 private PC의 newfstatat(AT_EMPTY_PATH)로 새 메타데이터를
읽는다. 원래 syscall 번호를 유지해 guest seccomp 거부가 계속 적용된다.
공유 경로 세대와 ID를 확인하고 결과를 안전하게 복사한다. FD가 있는 동안
신호를 막아 siglongjmp로 FD가 남지 않도록 한다. 지원하지 않는 경로·파일 종류,
실패·변경 감지는 원래 조회로 복귀한다. 메타데이터와 실패 결과는 캐시하지 않는다.

2026-09-17 Android MCP에서 `shared-stat-test.py`가 off/on 모두 통과했고 on에서
양수인 가속 hits를 확인했다. 파일 크기/ID·상대 경로·링크·EFAULT·삭제·seccomp를
검사했다. 새 옵션을 켠 FD 회귀(hits=653), cwd 및 공유 bind/chroot 회귀도 통과했다.
100회 stat 루프를 포함한 전체 Python 실행에서 enter_stop 517→299,
read_string 393→175, vm_write 489→271이었다. exit_stop은 104로 같고 prctl은
18/16(진입/반환)으로 같았다. 준비·시작·정리를 포함하므로 루프만의 계측은 아니다.
추적 감소는 확인했지만 시간 배속은 아직 측정하지 않았다.

ARM64 엔진·DSO 빌드와 Python 구문 검사, APK 설치가 통과했다. incremental
미지원은 streamed로 해결했다. 엔진 SHA-256은
`eb7fcae57d8e38b8c497c996c5569c11826d26ebaf1086be74a5f77cd6c4bf85`, DSO는
`facbee68797a75a483cfa190d854647a865796ada603ad08d3f46545228fefb2`다.
실험 네이티브 변경으로 Gradle·UI·외부 API/앱 MCP는 사용하지 않았다.
새 설치 통합·누적 시간 비교·제품 기본 적용은 남아 있다.

### 공유 stat를 포함한 Debian 설치

설치 실행기에 기본 비활성 `sharedStat`를 추가했다. 시험 인자
`rootlessSharedStat=true`로 켜며 fastfd DSO가 필요하고 notification 모드와
함께 사용하지 않는다. host FASTPATH와 guest FASTSTAT_SHARED 전달을 분리한다.
설치 시험은 기존 path DSO를 끈 상태에서 양수인 path stat hits를 요구한다.

2026-09-17 Android MCP의 API35 ARM64 redroid/ext4에서 eb7fcae5... 엔진과
facbee68... DSO, ID/TCGETS와 기존 파일 가속을 켠 새 Debian 설치가
85.992초에 통과했다. 공식 패키지 설치·기본 환경·하드링크·상대 파일 내용 및
stat/fstat/open/create/unlink 가속 사용을 확인했다. 권한 변경 실험 옵션은 끈
상태다. 설치 시간은 속도 향상 증거로 사용하지 않는다.

JDK 17 정규 assembleDebug/runtime 단위 시험/image 시험과
assembleDebugAndroidTest가 통과했다(170 tasks). 시험 APK 서명 검증과 설치가
통과했고 incremental 미지원은 streamed로 해결했다. 전체 성능 비교는 반복하지
않았다. UI/외부 API/외부 앱 변경이 없어 다른 MCP는 사용하지 않았다.
누적 시간 비교와 일반 사용자 세션의 제품 기본 적용은 남아 있다.
