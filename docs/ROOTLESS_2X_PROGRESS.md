# Rootless 2배 성능 개선 진행 기록

2026-09-15의 새 지시는 기존 버전과의 호환성보다 배포판 설치·정상 동작을
우선하며 기존 대비 2배 가속을 요구한다. 이전 실현 가능성 문서의 완화된
완료 기준은 이 작업에 적용하지 않는다. **현재 2배 목표는 미달성이다.**

## 1단계: 새 설치의 실제 하드링크

기존 추출기는 실제 hardlink를 만들지만 모든 실행은 l2s를 사용했다.
새 설치는 staging에서 link와 원본 unlink 후의 내용 보존을 시험하고,
저장 형식 마커를 기록한 뒤 활성화한다. setup 및 터미널의 공통 launcher는
이 형식에서 `--link2symlink`를 생략한다. fake-root는 패키지 설치에 필요하여
유지한다. Arch의 읽기 전용 root mode는 검사와 마커 기록 뒤 복구한다.

기존 rootfs의 자동 변환은 구현하지 않았다. 기존 사용자 데이터와 실행 중인
세션을 바꾸지 않기 위해 마커 없는 이미지는 기존 실행을 유지한다. 새 형식의
지원 경로는 새 설치이며, 사용자에게 데이터 삭제나 재설치를 실행하지 않았다.

[7쌍 원본 측정](../tools/rootless/faststat/results/native-links-2026-09-15.json)은
동일 redroid ARM64 Debian userspace에서 새 임시 파일을 생성하고 동일 설치
PRoot의 l2s 옵션만 교대했다. 공개 함수 DSO 가속은 사용하지 않았다.

| 작업 | 기존(ms) | 실제 링크(ms) | 배속 |
| --- | ---: | ---: | ---: |
| metadata 1,000개 | 65.295 | 57.692 | 1.13 |
| 파일 생성·변경·삭제 | 366.663 | 339.535 | 1.08 |
| Python import | 196.167 | 191.460 | 1.02 |
| tar 추출 | 897.956 | 834.942 | 1.08 |

새 배포판 전체 설치의 성능을 이 수치로 주장하지 않는다. 기존 userspace를
사용했으며 입력은 warm, 단일 작업이다. 이 변경만으로 2배에 도달하지 못한다.

## 검증과 미완료 항목

- 새 저장 형식·권한 복원·launch·archive 추출의 집중 단위 시험 통과.
- JDK 17 표준 `:android-app:assembleDebug :android-runtime:testDebugUnitTest
  :image:test --console=plain` 통과(138 tasks).
- Android MCP에서 임시 독립 dpkg root/database에 하드링크가 포함된 시험
  패키지의 설치·업그레이드·제거 통과. 기존 dpkg DB는 변경하지 않았다.
  최초 fixture는 umask로 control 디렉터리가 0777이 되어 거부되었고,
  시험에 `umask 022`를 명시한 뒤 통과했다. 배포판 전체 설치 증거는 아니다.
- 새 APK를 사용자 앱에 설치하지 않았다. 새 설치부터 환경 구성 완료까지의
  실기기 검증, Ubuntu/Arch 패키지 검증, stock 보안 정책 검증은 남아 있다.
- Android MCP로 측정했다. UI·외부 서비스·새 외부 API 변경이 없어 다른
  MCP의 추가 호출은 필요하지 않았다. 벤치마크 파일 전송은 자동 검토에서
  처음 거부되었으며 사용자의 명시적 전송 승인 후 성공했다.

다음 구현은 일반 경로에서 tracer 개입을 줄이는 구조를 대상으로 한다.
하드링크 제거의 작은 개선을 최종 완료 조건으로 대체하지 않는다. 최종적으로
새 배포판 설치, 패키지·개발 도구 동작, 정의된 대표 작업의 기존 대비 2배를
함께 검증해야 한다. Git/패키지/빌드 등 전체 작업과 큰 입력의 기준선을
확보하고 개선 적용률을 확인하는 작업도 남아 있다.

## 2단계: stat 종료 정지 제거(실험)

`PROOT_BUILD_FASTSTAT=1` 빌드에 `PROOT_STAT_ENTRY=1`을 주면 ARM64 stat을
진입 시 처리한다. PRoot의 경로 변환 뒤 tracer가 새 lstat 결과를 읽고
fake-root 소유자를 적용해 guest에 전달한다. 일반 파일·디렉터리만 처리하며
가속할 수 없는 경우에는 원래 syscall과 종료 처리를 유지한다. BPF는
ARM64 번호 79를 다루며 내부 이름은 `PR_fstatat64`임을 확인했다.

preload가 필요 없어 정적 ELF와 libc 내부의 해당 syscall에도 적용된다.
진입 정지와 경로 해석은 남아 있으며 0-stop 구현은 아니다. 기본 mountinfo
확장은 경로 변환만 하므로 허용하고, l2s 등 다른 확장이 있으면 가속하지 않는다.
결과 쓰기는 읽기 전용 mapping을 우회하지 않는 process_vm_writev를 사용한다.

[최종 7쌍 측정](../tools/rootless/faststat/results/stat-entry-2026-09-15.json):

| 작업 | 기존 PRoot+l2s(ms) | 새 실험+실제 링크(ms) | 배속 |
| --- | ---: | ---: | ---: |
| metadata | 63.830 | 40.700 | 1.57 |
| 파일 변경 | 367.987 | 335.262 | 1.10 |
| Python import | 197.622 | 181.702 | 1.09 |
| tar 추출 | 886.949 | 787.361 | 1.13 |

[별도 계측](../tools/rootless/faststat/results/stat-entry-counters-2026-09-15.json)은
동일 native-link 실험 빌드에서 STAT_ENTRY만 비교했다. 입력 준비와 Python
시작을 포함해 진입 9,009회는 같고, 종료 정지는 4,677 → 2,386회였다.
이 숫자는 timed stat 1,000회만의 카운터가 아니다.

Debian/Ubuntu의 정적 raw-syscall 시험이 파일 종류·크기·소유자·하드링크,
심볼릭 링크, AT_EMPTY_PATH, 잘못된 flags/FD/pointer, 읽기 전용 출력 메모리,
fork와 UID 변경, guest seccomp의 EPERM을 검증했다. seccomp 가속 끔과
mountinfo 확장 끔에서도 통과했다. 독립 dpkg root/database에서 설치·업그레이드·
제거도 통과했다. 사용자 앱과 기존 package DB는 교체하지 않았다.

초기 구현에는 내부 syscall 이름 불일치와 비활성화 조건 오류가 있었다.
이때 나온 tar 1.68배 등 수치는 채택하지 않았으며 결과 파일은 최종 빌드
측정으로 교체했다. l2s를 켠 시험의 fork 후 소유자 검사는 기존 바이너리와
실험 바이너리 양쪽에서 실패했다. 새 시험은 native-link 모드의 요구를 검증하며
이 l2s 동작을 통과했다고 주장하지 않는다.

**2배 목표는 여전히 미달성**이다. 다음 병목은 진입마다 반복하는 경로 해석이다.
커널이 경로를 한 번에 확인할 수 있는 일반 경로 처리를 검토하고, 기존의
바인딩·특수 파일 처리와 조합해야 한다. 전체 배포판 설치 및 실제 기기의
정책 검증도 계속 남아 있다. 제품 기본 payload에는 아직 이 실험을 넣지 않았다.

## 3단계: 일반 stat 경로의 커널 확인

`PROOT_STAT_ENTRY=1 PROOT_STAT_KERNEL=1`에서 일반 절대 경로와 현재 작업
디렉터리 기준 경로를 현재 binding으로 치환하고, tracer의 openat2
`RESOLVE_NO_SYMLINKS`와 fstat으로 확인한다. 성공한 파일·디렉터리는 결과를
바로 전달한다. 이 호출이 소유한 host FD는 반환 전에 닫고 guest에 노출하지
않는다. 파일 정보나 cwd/binding을 호출 사이에 캐시하지 않는다.

심볼릭 링크, 점 경로 구성 요소, 중복 슬래시, 특수 파일, 상대 dirfd 경로,
지원하지 않는 flags, 커널 거부는 기존 해석으로 돌아간다. 중첩 binding은
guest 쪽 상위 심볼릭 링크를 먼저 해석해야 할 수 있으므로 기존 경로를
사용한다. 기본 /root·/workspace 같은 최상위 binding은 가속할 수 있다.

[최종 7쌍 측정](../tools/rootless/faststat/results/stat-kernel-2026-09-15.json):

| 작업 | 기존 PRoot+l2s(ms) | 새 실험(ms) | 배속 |
| --- | ---: | ---: | ---: |
| metadata 1,000개 | 64.248 | 24.594 | **2.61** |
| 파일 변경 | 365.323 | 339.213 | 1.08 |
| Python import | 194.548 | 173.650 | 1.12 |
| tar 추출 | 894.918 | 763.562 | 1.17 |

조회 작업은 2배를 넘었지만 **전체 목표는 미달성**이다. 새 배포판 전체 설치와
대표 전체 작업의 2배를 이 제한된 warm 조회 측정으로 대신하지 않는다.
PRoot 진입 정지는 여전히 호출마다 필요하며 stat 외의 작업 비용이 남는다.

확장한 정적 raw-syscall 시험은 Debian/Ubuntu, /workspace binding, 중첩
binding fallback, 상위 심볼릭 링크, 절대·cwd 경로, 읽기 전용 출력 메모리,
UID 변경과 seccomp를 검증했다. 다음 설치 검증은 사용자 앱과 분리한
`:android-runtime`의 standalone Android test APK에서 실제 bootstrapper와
배포판 기본 환경 구성을 실행하도록 준비한다. 이 APK의 기본 PRoot는 아직
실험 payload가 아니므로, 그 설치 시험을 실험 경로 전체의 검증으로 혼동하지
않는다. 가속 payload의 새 설치 검증과 제품 적용은 별도로 남는다.

독립 시험 APK와 표준 Gradle 검증은 통과했다. 초기 공간 부족은 사용자 승인에
따른 재생성 가능한 캐시·이전 빌드 산출물 정리로 해결했다. JUnit 반환형 오류도
수정한 뒤 기본 payload로 Debian 새 설치 시험을 통과했다(`OK (1 test)`,
89.251초). 이 시간은 가속 전후 비교가 아니다.
[독립 설치 시험 절차와 제한](ROOTLESS_FRESH_INSTALL_TEST.md)을 기록했다.
커널 경로의 재현 빌드는 측정한 최종 SHA-256과 일치했고 loader digest는
기존 값과 동일하다. 가속 끔·seccomp 가속 끔의 회귀와 독립 dpkg 설치·업그레이드·
제거는 현재 사용 가능한 환경에서 통과했다.

## 4단계: 가속 옵션을 켠 새 Debian 설치

해시를 검증한 실험 PRoot와 STAT_ENTRY/STAT_KERNEL을 사용하는 독립 시험 APK로
새 이미지부터 기본 패키지 설치·환경 구성·하드링크 검증까지 통과했다.
[설치 시험 결과](ROOTLESS_FRESH_INSTALL_TEST.md)의 90.722초는 네트워크를
포함한 단일 검증 실행 시간이며 배속 비교가 아니다. 기본 앱 payload는
계속 별도로 유지한다. 전체 2배 목표는 미달성이고, stat 외 호출의 반복 경로
해석과 진입 정지 비용을 줄이는 구현 및 대표 전체 작업 측정이 남는다.

## 5단계: 일반 open·파일 변경 경로의 커널 해석

`PROOT_PATH_KERNEL=1`은 openat, mkdirat, unlinkat, renameat/renameat2,
linkat, fchmodat, utimensat, faccessat의 일반 경로를 가속한다. 부모를
openat2(NO_SYMLINKS)로 확인하고 마지막 구성 요소는 fd 기준 fstatat으로
확인한다. 일반 파일·디렉터리 또는 없는 최종 이름만 처리하며, 부모·최종
파일의 fake-root 권한 보정과 TRANSLATED_PATH 확장 처리는 유지한다.
호출 사이에 경로·메타데이터를 캐시하지 않는다. 커널 probe FD는 닫는다.

심볼릭 링크, 점 경로·중복/끝 슬래시, 중첩 binding, pseudo 경로, 다른 확장,
비활성 seccomp 및 ARM32는 기존 해석으로 돌아간다. 이 단계는 real syscall을
guest에서 계속 실행하므로 진입·종료 정지 횟수 자체는 줄이지 않는다.

[7회 교대 3종 비교](../tools/rootless/faststat/results/path-kernel-2026-09-15.json):

| 작업 | 기존 PRoot+l2s(ms) | stat 가속(ms) | 경로 가속 추가(ms) | 기존 대비 배속 |
| --- | ---: | ---: | ---: | ---: |
| metadata 1,000개 | 63.638 | 24.910 | 24.541 | 2.59 |
| 파일 변경 | 371.377 | 340.331 | 321.364 | 1.16 |
| Python import | 194.472 | 174.818 | 173.560 | 1.12 |
| tar 추출 | 895.980 | 763.995 | 728.586 | 1.23 |

별도 파일 변경 계측에서 canonical lstat는 30,111 → 450회,
path translation은 7,256 → 98회, 경로 가속 성공은 7,158회였다.
진입 14,012회와 종료 5,389회는 그대로다. 입력 준비·프로그램 시작을 포함하며
커널 probe나 fake-root의 권한 stat까지 모두 센 수치는 아니다.

정적 syscall 회귀와 Debian/Ubuntu의 파일 작업 회귀, workspace·중첩 binding,
상위·최종·dangling symlink, 읽기 제한 파일의 읽기·쓰기, rename/link/unlink,
seccomp 가속 끔을 확인했다. 회귀 시험에서 부모 0500→0700, 파일 0000→0600이
남는 동작은 기존 PRoot에서도 재현됐다. 새 경로가 권한 복원을 보장한다는
주장은 하지 않으며 별도의 기존 fake-root 결함으로 남는다.

**전체 2배 목표는 미달성**이다. 다음 병목은 경로 검사보다 syscall의 정지·재개
비용이다. FD를 새로 반환하지 않는 변경 호출의 tracer 내 완료를 검토해야 한다.
기본 앱 payload에는 아직 적용하지 않았다. stock Android 정책과 F2FS 특이
동작은 이 ext4 redroid 결과로 검증되지 않으며 제품 적용 전에 남은 과제다.

동일 최종 해시로 새 Debian 설치·기본 패키지 구성·하드링크 시험도 통과했다
(`OK (1 test)`, 87.349초). 표준 Gradle 검증 및 옵션 격리 단위 시험이 통과했다.
[설치 절차와 결과](ROOTLESS_FRESH_INSTALL_TEST.md)에 기본·stat·일반 경로
가속 시험을 구분했다. 설치 시간은 단일 네트워크 포함 실행으로 배속 비교가 아니다.

## 6단계: 일반 파일 rename의 진입 시 완료

`PROOT_MUTATION_ENTRY=1`은 native ARM64 renameat 및 flags=0 renameat2의
일반 파일 이동을 경로 변환·권한 보정 후 tracer에서 한 번 실행한다. 성공과
실패 모두 syscall 결과로 반환하고 원래 guest 호출은 취소한다. 실행 뒤
실패를 이유로 같은 변경을 재실행하지 않는다. 디렉터리·특수 파일·다른 flags,
다른 확장 및 seccomp 가속 끔은 기존 syscall 경로를 사용한다.

초기 구현은 unlink/link도 처리했으나 이 호출은 기존에 종료 정지가 없어서
오히려 fallback 비용이 늘었다. 최종 구현에서는 제외했다. renameat 디렉터리
이동의 기존 cwd 갱신을 건너뛰지 않도록 일반 파일만 처리한다.

[7회 교대 3종 측정](../tools/rootless/faststat/results/mutation-entry-2026-09-15.json):

| 작업 | 기존(ms) | 경로 가속(ms) | rename 가속 추가(ms) | 기존 대비 배속 |
| --- | ---: | ---: | ---: | ---: |
| metadata | 64.399 | 25.273 | 24.686 | 2.61 |
| 파일 변경 | 371.561 | 330.192 | 311.286 | 1.19 |
| Python import | 199.289 | 174.178 | 172.703 | 1.15 |
| tar 추출 | 896.222 | 738.834 | 738.796 | 1.21 |

새 단계의 추가 효과는 파일 변경의 약 5.7% 시간 단축이다. 다른 작업에
추가 향상을 주장하지 않는다. [별도 계측](../tools/rootless/faststat/results/mutation-entry-counters-2026-09-15.json)은
진입 14,012회를 유지하면서 종료 5,389 → 4,389회를 확인했다. 대신 변환한
경로를 읽는 비용이 늘었다. 준비·시작을 포함하는 수치다.

정적 raw syscall 시험에 renameat/renameat2 성공, EISDIR, EEXIST,
잘못된 포인터·flags, renameat 디렉터리 이동 후 cwd, guest seccomp의
stat/unlink/link/rename/renameat2 거부를 추가했다. Debian, Ubuntu workspace,
seccomp 가속 끔에서 통과했다. 셸의 권한·변경·링크 회귀도 통과했다.
시험 코드의 누락된 string.h는 수정 후 경고를 오류로 처리하는 빌드에 성공했다.

최종 실험 SHA-256은
`e7f7f3e6cbebb90d121a7c0ad4ad0b4e10a655e30e30e47d0239602ac7ea5a9c`다.
표준 Gradle 검증·옵션 격리 단위 시험·시험 APK 빌드가 통과했고 같은 해시로
새 Debian 설치·기본 패키지·환경·하드링크 검증도 통과했다(88.048초).
이 설치 시간은 단일 네트워크 포함 실행이므로 배속 비교에 쓰지 않는다.

**전체 2배 목표는 미달성**이다. 기본 앱 payload 적용, stock Android/F2FS
검증도 남아 있다. 다음 성능 과제는 open 등 FD를 반환하는 호출과 남은
진입 정지 비용이며, 현재 방식만 더 넓혀 충분한 개선이 날 것으로 단정하지 않는다.

최종 검토에서 변환한 이동 경로를 다시 읽을 때 read_path의 길이 검사를
재사용하도록 보완하고 ENAMETOOLONG 회귀를 추가했다. 이후 측정 7회와
새 설치를 다시 실행했으며 위 표·결과 파일은 보완 후 최종 바이너리 기준이다.
현재 시험 커널(6.17.0-1020-oracle)은 seccomp actions_avail에 user_notif를
열거한다. 이는 다음 FD 전달 실험의 단서이며 앱 UID에서 listener/ADDFD가
동작한다는 증거는 아직 아니다.

## 7단계: 비특권 파일 디스크립터 전달 가능성 검증

[별도 전송 실험](../tools/rootless/notify/README.md)을 구현했다. 고정 파일의
USER_NOTIF/ADDFD_SEND 전달, 메모리 읽기, CLOEXEC, 오류 및 취소 요청 검사를
Android 앱 UID에서 통과했다. 중앙 실행 로더로 실행하는 별도 앱 시험도
통과했다. APK에 포함한 고정 시험 이름만 허용하며 제품 APK에 실행 파일을
추가하거나 기존 PRoot를 교체하지 않는다.

10000회 open/close의 7회 교대 중앙값은 native 16.986ms, 고정 파일 전달
141.996ms, 현재 실험 PRoot 468.299ms, 기존 PRoot 517.189ms였다.
약 3.30배의 전송 가능성을 확인했지만 일반 경로 변환과 FD 상태 관리가
빠진 시험이므로 **전체 rootless 2배 목표는 여전히 미달성**이다.

표준 Gradle 및 시험 APK 빌드가 통과했다. 앱 instrumentation은 0.117초,
1개 시험을 통과했다. 호스트 샌드박스에서는 메모리 읽기 EPERM으로 실패했다.
Android 시험 앱 역시 초기 Seccomp=0이어서 stock 단말의 정책 검증은 남는다.
다음 구현은 PRoot의 경로/dirfd/umask 의미와 ptrace 이벤트 루프에 전송을
통합하고 실제 설치·파일 변경·import·tar 작업으로 다시 측정하는 것이다.

설치 시험을 막았던 디스크 부족은 재생성 가능한 임시 Rust target과 비활성
모듈 app/build를 정리해 해소했다. 사용자 데이터와 rootfs는 보존했고,
이후 6단계 최종 payload의 새 Debian 설치 시험이 통과했다.

## 8단계: 파일 I/O 목표와 기준 측정

사용자가 추가한 파일 I/O 목표를 기존 전체 2배 목표에 포함한다. 기존의
metadata/mutation/import/tar만으로 읽기·쓰기 속도 개선을 입증하지 않는다.
`workloads.py`에 작은 파일 읽기, 순차 읽기·쓰기, 최종 fsync 포함 쓰기를
추가하고 바이트 수·MiB/s를 기록한다. 내용 검증과 short I/O 처리를 포함하며
각 실행이 만든 임시 파일만 사용한다. 동기화를 생략해 성능을 부풀리지 않는다.

[56개 측정 결과](../tools/rootless/faststat/results/file-io-2026-09-16.json)는
기존 제품과 6단계 실험 PRoot를 같은 Debian·앱 전용 ext4에서 7회 교대로
실행한 값이다. 작은 파일은 448바이트 1000개, 순차 전송은 64MiB이고
64KiB 블록을 쓴다. 준비·정리는 제외하며 읽기는 warm cache다.

| 작업 | 기존 중앙값(ms) | 실험 중앙값(ms) | 배속 |
| --- | ---: | ---: | ---: |
| 작은 파일 읽기 | 184.934 | 169.475 | 1.09 |
| 순차 읽기 | 13.457 | 13.747 | 0.98 |
| 순차 쓰기 | 13.809 | 13.820 | 1.00 |
| fsync 포함 쓰기 | 770.511 | 832.159 | 0.93 |

동기화 쓰기는 기존 508~832ms, 실험 680~860ms로 편차가 컸다. 이 결과를
개선이나 실험 패치가 원인인 확정적 회귀로 단정하지 않는다. 저장 장치를
공유하는 시험 환경의 변동과 fsync 대기 비용을 포함한다. 순차 쓰기에서
fsync를 빼면 의미가 달라지므로 두 결과를 별도로 유지한다.

일반 read/write는 현재 seccomp 필터가 이미 추적을 건너뛰지만 open/close는
추가 처리가 있다. close는 auxv/netlink FD 상태와 pipe shadow 처리를 하므로
일괄 추적 해제만으로 안전하게 개선할 수 없다. 다음 엔진 변경은 open의
FD 전달을 경로 의미와 이벤트 루프에 통합하는 것이다. 현재 이벤트 루프의
blocking waitpid 및 pipe shadow 타이머와 notification 대기를 함께 다뤄야 한다.

호스트에서 9개 전체 작업을 2회 실행했고 기기에서 56개 I/O 측정 모두
내용 검증을 통과했다. Android MCP shell의 최초 실행은 APK 경로의 `=`가
env 할당으로 해석되어 실패했다. 명시적 sh 실행으로 수정 후 측정했다.
이번 변경은 벤치마크와 문서만 바꾸므로 Gradle/새 설치는 재실행하지 않았다.
UI 및 외부 서비스 변경이 없어 replicant/context7/codex_apps는 사용하지 않았다.
**전체 2배 및 파일 I/O 개선은 아직 달성하지 못했다.**

## 9단계: 읽기 전용 open의 PRoot 통합과 부정적 성능 결과

[notification 통합](../tools/rootless/notify/README.md)을 별도 빌드·실행 옵션으로
구현했다. 실제 binding/경로 변환, 단순 경로의 kernel 검증, FD 전달과
ptrace/notification 혼합 이벤트 대기를 연결했다. 종료 경쟁 조건을 수정하고
파일 읽기·flags·오류·동시 자식·자식 종료·guest seccomp 회귀를 통과했다.

그러나 이전 최선의 e7f... 실험판과 7회 교대로 비교한 결과 작은 파일 읽기는
158.827→167.190ms, import는 173.323→179.083ms, tar는 732.823→785.160ms로
느려졌다. 순차 I/O는 대체로 같았다. 별도 진단에서 1129개 notification 중
1078개가 단순 경로로 처리되어 실제 통합 경로가 실행됐음은 확인했다.
고정 파일 전송 실험의 3.30배 향상은 일반 작업으로 이어지지 않았다.

새 코드는 실험 옵션으로만 보존하며 기본 채택을 보류한다. FIFO/장치 파일,
auxv 후처리 및 guest ptracer 지원은 미완성이고 새 설치는 검증하지 않았다.
따라서 일반 목적 rootless backend나 목표 달성으로 보고하지 않는다.
다음 최적화에서는 작은 파일 읽기의 fstat/close 추적 비용도 분리해 다룬다.

최종 해시·84개 측정·검증 범위는 위 문서와 원시 JSON에 기록했다. Android MCP로
별도 APK와 Debian runtime을 검증했으며 증분 설치 불가 시 streamed 설치가
성공했다. 이번 변경은 별도 C 엔진/시험 도구이며 Android 모듈 코드는 바꾸지
않아 Gradle은 재실행하지 않았다. UI·외부 앱·새 외부 라이브러리 작업이 없어
replicant/codex_apps/context7는 사용하지 않았다. **목표는 계속 미달성이다.**

## 10단계: 열린 파일 fstat의 진입 완료

`PROOT_FSTAT_ENTRY=1`을 추가했다. native ARM64 fstat 진입에서 현재
`/proc/<pid>/fd/<fd>`의 inode 메타데이터를 읽고 guest 버퍼에 반환한다.
일반 파일·디렉터리만 처리하며 fake UID/GID 보정을 유지한다. 이름이나
메타데이터를 호출 간에 캐시하지 않는다. procfs 접근 실패, 특수 파일,
다른 ABI/확장, 쓰기 불가능하거나 일부만 쓸 수 있는 버퍼는 기존 커널과
종료 콜백으로 넘긴다. guest seccomp 거부를 우회하지 않는다.

[7회 교대 비교](../tools/rootless/faststat/results/fstat-entry-2026-09-16.json):

| 작업 | 이전 실험(ms) | fstat 추가(ms) | 배속 |
| --- | ---: | ---: | ---: |
| metadata | 27.169 | 30.889 | 0.88 |
| 파일 변경 | 304.737 | 301.356 | 1.01 |
| 작은 파일 읽기 | 159.768 | 140.570 | 1.14 |
| Python import | 172.687 | 169.136 | 1.02 |
| tar 추출 | 733.466 | 714.378 | 1.03 |
| 순차 읽기 | 13.181 | 13.128 | 1.00 |
| 순차 쓰기 | 13.758 | 13.575 | 1.01 |
| fsync 포함 쓰기 | 280.942 | 280.192 | 1.00 |

작은 파일 읽기 시간은 약 12.0% 줄었다. 별도 계측에서는 진입 정지 13009회를
유지하면서 종료 정지가 5386→2179회로 줄었다. 계측에는 준비·정리가 포함되며
위 시간 비교에서는 진단 출력을 껐다. 비교 대상은 제품 원본이 아닌 이전
e7f... 실험판이고, 9단계 notification 통합은 사용하지 않았다.

metadata의 악화를 확인하기 위해 [별도 반복 비교](../tools/rootless/faststat/results/fstat-metadata-audit-2026-09-16.json)를
수행했다. 이전 바이너리·새 바이너리 옵션 끔·옵션 켬을 정순/역순으로 실행하고
각 조건 20회 측정한 중앙값은 25.449/26.1145/24.9705ms였다. 혼합 작업에서
관찰한 12% 악화는 이 시험에서 재현되지 않았다. 최초 수치를 삭제하거나
metadata 향상으로 바꾸지 않으며 원인별 계측은 아직 남는다.

최종 SHA-256은 `b8ea54de9d0e32c02ad4d3d28d43aee3f2fb99c338cd73a337c87bb38f095b6f`.
원시 syscall 시험은 크기 변경, 삭제된 inode, 닫힌 FD·FD 재사용, 디렉터리·pipe,
잘못된 포인터, 읽기 전용·부분 버퍼, fake UID와 seccomp 거부를 확인한다.
Debian, Ubuntu workspace, seccomp 가속 끔 및 이전 실험판에서 통과했다.

표준 Gradle 검증·시험 APK 빌드(170 tasks, 15 executed)와 다섯 환경 변수의
옵션 격리 단위 시험이 통과했다. Android MCP에서 같은 해시로 새 Debian
설치·기본 패키지·환경·하드링크 검증도 통과했다(`OK (1 test)`, 88.286초).
이 단일 네트워크 포함 설치 시간은 성능 배속 근거가 아니다.
Gradle의 최초 실행은 샌드박스 캐시 쓰기 제한으로 실패했고 승인된 재실행은
성공했다. APK incremental 설치 미지원은 streamed 설치로 해결했다.

기본 앱 payload는 바꾸지 않았다. stock Android/F2FS 검증과 전체 작업 2배는
여전히 남는다. 순차 I/O 개선도 확인하지 못했으며 다음 병목은 남은 파일
열기·닫기와 경로 처리 비용이다. **전체 목표는 미달성이다.**

## 11단계: close 추적과 권한 모사 비용 분리

[파일 작업 한정 close probe](../tools/rootless/close-probe/README.md)를 별도
빌드 옵션으로 구현했다. 일반 close도 추적하며 pipe 여부를 읽는 비용을
생략했을 때 얻을 수 있는 효과를 측정한다. 특수 FD bookkeeping을 생략하므로
일반 실행에 적용할 수 없으며 제품 기본값을 바꾸지 않았다.

7회 교대 네 조건 비교에서 같은 가상 root의 작은 파일 읽기는
141.536→121.492ms(시간 약 14.2% 감소), tar는 705.887→660.795ms였다.
별도 계측의 진입 정지는 13009→10848회로 줄었고 종료 정지는 같았다.
metadata는 오히려 느렸고 순차 I/O는 대체로 같았다. 모든 수치를 원시
결과에 남겼다. 비교 기준은 10단계 실험판이며 제품 원본 대비 배속이 아니다.

가상 root 자체를 끄면 작은 파일 읽기 91.806ms, tar 329.355ms였다.
하지만 기기의 tarfile 구현은 root일 때만 사용자/그룹 조회와 chown을 하므로
이 비교에는 작업량 변화가 포함된다. 가상 UID 1000의 후속 단일 확인에서도
tar는 빨라졌지만 작은 파일 읽기는 root 모드와 비슷했다. 따라서 일반 UID의
배속을 권한 의미가 같은 엔진의 성능 향상이나 전체 목표 달성으로 인정하지 않는다.

다음 과제는 특수 FD 처리를 유지하는 일반 파일 close 가속이다. 실행 identity를
바꾸는 접근은 사용자 계정과 패키지 관리 경로까지 별도로 검증해야 한다.
ARM64 빌드와 Android MCP의 224개 파일 작업 내용 검사는 통과했지만
이 probe의 새 설치·네트워크·파이프 정상성은 검증하지 않았다. Android 모듈
변경이 없어 Gradle은 재실행하지 않았다. **전체 2배와 일반 파일 I/O 개선의
제품 적용 목표는 계속 진행 중이다.**

## 12단계: close 상태 관리를 유지한 파이프 보정 생략

faststat 실험 빌드에 `PROOT_KERNEL_PIPE=1`을 구현했다. close의 ptrace 정지와
auxv/netlink 상태 정리는 유지하고, 모든 close에서 수행하던 파이프 확인 및
추가 읽기 끝 생성만 생략한다. 옵션은 기본 비활성이다. 소비자가 사라진
writer의 SIGPIPE/EPIPE를 기존 보정처럼 지연시키지 않는 의미 변화가 있다.

[7회 교대 112개 기록](../tools/rootless/faststat/results/kernel-pipe-2026-09-16.json):
작은 파일 읽기 141.682→136.113ms(1.04배), 파일 변경 304.241→295.235ms,
tar 712.435→696.889ms였다. metadata 26.168→28.375ms와 순차 읽기
13.018→13.658ms는 악화됐고, 나머지는 대체로 같았다. 측정 기준은 최초
제품이 아닌 b8ea fstat 실험판이다. 의미 있는 전체 2배 개선으로 보지 않는다.

ARM64 빌드, APK 서명·설치, 파일 작업 내용 검사와 별도 기능 시험을 통과했다.
기능 시험은 20회 파이프 전달·조기 종료·프로세스 치환 및 임시 dpkg 루트의
자체 패키지 설치·내용 확인·제거다. 최초 시험은 상속된 umask 때문에 패키지
control 디렉터리가 0777로 생성돼 실패했다. 시험에 umask 022와 관리자 PATH를
명시한 뒤 통과했다. 새 배포판 설치나 maintainer script 검증을 대신하지 않는다.
Android MCP 증분 설치 실패는 streamed 설치로 해결했다. 모듈 코드 변경이 없어
Gradle은 재실행하지 않았고 UI/외부 API 작업이 없어 다른 MCP는 쓰지 않았다.

이 후보는 기본 적용하지 않는다. 사용자 지적을 반영해 작은 변경마다 전체
7회 비교를 반복하는 흐름을 중단한다. 이후 관련 엔진 변경을 묶고, 개발 중에는
영향받는 작업의 짧은 확인과 기능 회귀를 우선한다. 전체 반복 비교는 채택할
후보의 최종 검증에 수행한다. **전체 2배 및 파일 I/O 목표는 미달성이다.**

## 13단계: notification 대기를 별도 스레드로 분리

`PROOT_OPEN_NOTIFY_THREAD=1` 실험 옵션을 구현했다. 일반 ptrace 이벤트는
blocking waitpid를 사용하고 별도 스레드가 notification을 기다린다. 요청별
타이머 arm/disarm을 없앴다. 공유 Tracee/확장/talloc 접근을 직렬화하고,
종료 시 RECV를 중단·join한 뒤 상태를 정리한다. 기본값은 비활성이다.

ARM64 빌드, APK 서명/설치, raw syscall 오류·seccomp·동시 자식·자식 종료
회귀를 통과했다. 추가한 8스레드/800회 열기 검사는 링크/dirfd, 내용, fstat,
CLOEXEC를 검증하며 새 방식·기존 notification·비활성 모드에서 모두 통과했다.
Android MCP를 사용했고 incremental 설치 실패는 streamed 설치로 해결했다.
Android 모듈 변경이 없어 Gradle은 재실행하지 않았다. UI/외부 API 변경이 없어
다른 MCP는 사용하지 않았다. patch 파일의 정상적인 context 공백은 기존 patch와
같은 .gitattributes 규칙으로 처리했다.

[단회 확인](../tools/rootless/notify/results/thread-spot-2026-09-16.json)에서
작은 파일 읽기만 개선 방향이었고 import/tar의 이점은 확인하지 못했다.
이는 개발 확인이며 확정적인 성능 비교가 아니다. 전체 7회 비교를 반복하지 않았다.
[구조와 검증 범위](../tools/rootless/notify/README.md)에 해시와 제약을 기록했다.
특수 파일·auxv·guest ptracer와 새 배포판 설치 검증이 남아 있으므로 기본 적용하지
않았다. **전체 2배 및 파일 I/O 목표는 계속 미달성이다.**

## 14단계: notification 후보의 기본 장치·auxv와 실제 Debian 설치

이전 후보가 거부하던 기본 메모리 장치 다섯 종류의 읽기 전용 열기를 구현했다.
고정한 inode의 장치 번호로 범위를 제한하며 임의 장치·TTY·FIFO는 여전히 거부한다.
자기 auxv는 AT_EXECFN을 보정한 sealed 읽기 전용 스냅샷으로 제공한다.
부분 read/pread/dup은 지원하지만 procfs inode·크기·readlink 표현은 보존하지 않는다.

`special-test.py`의 장치·flags·내용·FIFO 거부·auxv 분할 읽기와 seal 검사가
스레드/기존 대기 방식에서 통과했다. raw syscall 및 8스레드/800회 읽기 회귀도
통과했다. 설치 시험에 명시적 notification 스레드 옵션과 일반 실행으로의
환경 변수 누출 방지 단위 시험을 추가했다. 표준 Gradle과 시험 APK 빌드가
170 tasks, 15 executed로 통과했다.

최종 14463e... 후보의 digest를 검증한 별도 APK에서 **새 Debian 설치·공식
기본 패키지 구성·환경·하드링크 검증이 통과**했다(`OK (1 test)`, 87.019초).
임시 설치 디렉터리가 남지 않은 것도 확인했다. Android MCP를 사용했고
incremental 설치 미지원은 streamed 설치로 해결했다. 장치 번호는 Linux 커널
문서로 확인했다. 다른 MCP는 UI/외부 서비스/API 변경이 없어 사용하지 않았다.

성능 비교는 추가 실행하지 않았다. 설치 시간은 배속 근거가 아니며, FIFO/TTY,
guest ptracer와 stock Android/F2FS 검증은 남는다. 제품 기본 엔진도 유지했다.
[후보의 구현·검증 범위](../tools/rootless/notify/README.md)와
[새 설치 절차](ROOTLESS_FRESH_INSTALL_TEST.md)에 기록했다.
**전체 2배 및 파일 I/O 성능 목표는 계속 미달성이다.**

## 15단계: notification의 익명 파이프·비대기 FIFO 지원

익명 파이프의 읽기 전용 재열기와 O_NONBLOCK named FIFO를 처리한다.
O_PATH로 고정한 inode의 종류와 파일시스템을 확인해 writer를 기다릴 수 있는
named FIFO를 구분한다. flags를 바꾸거나 dup으로 대체하지 않는다. 대기 가능한
named FIFO는 별도 설계가 필요해 계속 명시적으로 거부한다.

ARM64 빌드와 Android MCP의 파이프 회귀(스레드/기존 대기 방식)가 통과했다.
20회 셸 파이프·조기 종료·프로세스 치환, 임시 dpkg 루트의 자체 패키지
설치·제거, 장치/auxv, 8스레드/800회 열기 검사도 통과했다. 원본 프로세스의
FD flags와 재열기한 FD flags가 독립적인지, /proc/fd 경로의 named FIFO를
익명 파이프로 오인하지 않는지도 검사했다.

후보는 1495a9...이며 [구현과 검증 기록](../tools/rootless/notify/README.md)에
전체 해시를 남겼다. 커널 5.10/6.1/6.17의 pipe 구현으로 대기 조건을 확인했다.
APK 증분 설치 실패는 streamed 설치로 해결했다. UI/외부 앱 변경이 없어
다른 MCP는 사용하지 않았다. 성능 비교와 새 설치는 반복하지 않았고 Android
모듈 변경이 없어 Gradle도 재실행하지 않았다. 기본 엔진은 유지했으며
**전체 2배 및 파일 I/O 성능 목표는 계속 미달성이다.**

## 16단계: 프로세스 내부 조회와 fstat 진입 가속의 필터 충돌 수정

기존 DSO 가속과 FSTAT_ENTRY를 함께 활성화하면 내부 전용 fstat 호출까지
진입 추적되는 순서 문제가 있었다. seccomp의 전용 PC/architecture/syscall
검사를 일반 fstat 진입 규칙보다 먼저 배치했다. 허용 syscall 범위는 유지한다.

새 `trampoline-test.c`로 기존 1495a9... 엔진의 실패를 재현하고, 수정한
9312f4... 엔진의 통과를 확인했다. 일반 fstat/getuid의 가상 소유자, 내부
fstat의 실제 정보, 삭제한 inode의 128회 크기 변경, guest seccomp의 양쪽
경로 거부를 검증한다. notification 스레드 결합에서도 통과했다.
기존 DSO 회귀도 hits=831/fallbacks=11로 통과했다. 이 값은 기능 경로 확인이며
속도 비교 수치가 아니다. 잘못된 포인터의 PEEKDATA 경고는 예상된 검사다.

ARM64 엔진과 guest DSO/driver 빌드, 별도 APK 서명·Android MCP 설치가 통과했다.
incremental 설치 미지원은 streamed 설치로 해결했다. 시험 파일을 선택적으로
APK에 넣는 빌드 옵션을 추가했다. 성능 비교·새 설치·Gradle은 반복하지 않았고
UI/외부 서비스/API 변경이 없어 다른 MCP는 사용하지 않았다.
[검증 범위](../tools/rootless/faststat/README.md)에 결과를 남겼다.
열린 FD의 일반 fstat/close 전체를 직접 실행하는 후속 설계는 아직 미구현이며
**전체 2배 및 파일 I/O 성능 목표는 계속 미달성이다.**

## 17단계: 공유 자격 정보를 이용한 프로세스 내부 fstat

스레드별 UID/GID 공유 페이지와 DSO fstat/fstat64를 구현했다. 파일 정보는 매번
커널에서 읽고 fake-root 소유자만 프로세스 안에서 변환한다. tracer 왕복을
줄이되 UID/GID 변경은 재개 직전에 반영한다. fork/exec와 스레드 종료 시
매핑을 각각 관리하며, 사용할 수 없으면 기존 syscall 경로로 복귀한다.

엔진 a25b410f... 빌드 및 Android MCP 기능 시험이 통과했다. notification
스레드 활성/비활성 모두 새 시험 hits=651, 기존 stat 회귀 hits=831/fallbacks=11.
크기 변경·unlink·FD 재사용·8스레드별 saved-ID 변경·fork·exec·EFAULT·seccomp와
공유 페이지 생성 실패를 검사했다. 자세한 범위와 제약은
[faststat 문서](../tools/rootless/faststat/README.md)에 기록했다.

성능 비교를 반복하지 않았으므로 이 단계의 속도 향상률은 아직 없다. glibc
2.34 이상을 요구하는 실험 DSO이며 static/hidden 호출은 가속하지 않는다.
기본 제품 엔진은 유지했다. 새 설치/Gradle은 재실행하지 않았다. Android MCP
설치의 incremental 실패는 streamed로 해결했다. UI나 외부 API 변경이 없어
다른 MCP는 필요하지 않았다. 신호 재진입과 자원 수명 검토, 실제 파일 작업의
후보 성능 검증과 새 배포판 설치 검증이 남았다.
**전체 2배 및 파일 I/O 성능 목표는 계속 미달성이다.**

## 18단계: fstat 공유 페이지의 신호 재진입 오류 수정

스레드 종료 시 페이지 해제 직후 신호가 들어오면 이전 DSO가 SIGSEGV로
종료하는 문제를 기기에서 재현했다. fstat 전체·fork 매핑 교체와 destructor에
atomic 재진입 표시를 적용했다. 그동안 신호 처리기의 fstat은 일반 경로를 쓴다.
같은 재현 시험과 기존 fd-test가 notification 비활성/스레드 활성 모두 통과했다.

같은 엔진/DSO의 fastfd off/on 작은 파일 읽기 각 1회에서
146.390ms → 109.251ms였다. 25.4% 시간 감소라는 방향성은 확인했지만,
단일 순차 비교이므로 확정 향상률이 아니다. 전체 성능 비교는 반복하지 않았다.
[재현 조건과 기록](../tools/rootless/faststat/results/fastfd-signal-2026-09-16.json)을
남겼다. guest 빌드·APK 서명·Android MCP 시험은 통과했고 incremental 설치의
미지원은 streamed로 해결했다. 다른 MCP가 필요한 변경은 없었다.

첫 신호 호출의 초기화 안전성·비지역 탈출 후 가속 복구, 후보 전체 성능 및
새 배포판 설치 검증은 남아 있다. Android 모듈을 바꾸지 않아 Gradle은 생략했다.
**전체 2배 및 파일 I/O 성능 목표는 계속 미달성이다.**

## 19단계: 첫 신호 호출의 동적 초기화 제한

guest DSO를 initial-exec TLS와 즉시 함수 연결로 빌드한다. glibc NPTL의
추가 할당 없는 첫 32개 pthread 키 안에서만 fastfd를 활성화하고, 그 밖이면
일반 경로를 사용한다. 구현 의존성과 소스 근거는
[faststat 문서](../tools/rootless/faststat/README.md)에 기록했다.

Android MCP로 guest glibc 2.41을 확인했다. 새 스레드의 최초 fstat을 신호
처리기에서 실행하는 검사와 기존 fd-test가 notification 비활성/스레드 활성
모두 통과(hits=653)했다. 32개 키를 먼저 점유한 시험에서는 가속이 비활성화되고
일반 fstat의 결과가 유지됨을 확인했다. guest 빌드·ELF TLS/연결 방식 확인·APK
설치가 통과했다. incremental 설치 실패는 streamed 설치로 해결했다.
외부 glibc 소스 조회 일부가 실패해 코드 브라우저와 읽을 수 있는 태그 소스를
사용했다. UI 변경이 없어 replicant 등 다른 MCP는 사용하지 않았다.

성능 비교·새 배포판 설치·Gradle은 이번에 반복하지 않았다. 비지역 탈출 시
자원 수명 검토와 후보 전체 성능/배포판 검증이 남는다.
**전체 2배 및 파일 I/O 성능 목표는 계속 미달성이다.**

## 20단계: 초기화 도중 비지역 탈출의 FD 누수 수정

F_ADD_SEALS 직전에 신호 처리기에서 siglongjmp하도록 만든 시험에서 이전
DSO의 FD 누수를 재현했다. 공유 페이지 최초 연결 중에만 신호를 미루고
FD 정리와 destructor 등록 후 복원하도록 수정했다. 정상 반복 경로에는
추가 마스크 syscall이 없다. 수정 후 16개 스레드의 탈출을 반복해도 FD와
공유 페이지 수가 유지됐으며, 신호 마스크와 이후 파일 조회도 정상이었다.

Android MCP에서 notification 비활성/스레드 활성 회귀가 통과했다. memfd와
신호 마스크 syscall을 각각 seccomp로 거부했을 때의 일반 경로도 통과했다.
DSO를 실제 bash/dpkg에 preload해 셸 파이프·조기 종료·프로세스 치환 20회와
임시 패키지 생성·설치·내용 비교·제거를 검증했다. 기존 패키지 DB는 그대로다.
[구현 범위와 DSO 해시](../tools/rootless/faststat/README.md)를 기록했다.

비지역 탈출 뒤에는 해당 스레드가 일반 경로를 사용하며 가속 자동 복구는
미구현이다. guest 빌드·APK 서명·설치가 통과했고 incremental 미지원은
streamed로 해결했다. UI/외부 API 변경이 없어 다른 MCP는 사용하지 않았다.
성능 비교·Gradle·새 배포판 설치는 이번 단계에 포함하지 않았다. 후보의
배포판 전체 설치와 대표 성능 검증이 남는다.
**전체 2배 및 파일 I/O 성능 목표는 계속 미달성이다.**

## 21단계: 공유 자격 정보 후보의 새 설치 및 대표 작업 비교

설치 명령에 명시적인 guest DSO 옵션을 추가하고, 단위 시험으로 host LD_PRELOAD
미설정과 일반 설치의 환경 격리를 확인했다. 독립 시험 APK에서 엔진과 DSO의
SHA-256을 검증한다. 새 Debian 설치·패키지/기본 환경·하드링크·실제 가속 fstat
확인이 92.070초에 통과했고 시험용 설치 디렉터리는 남지 않았다. 초기 해시
인자 오류와 종료 진단 미수집 실패는 수정 후 재실행했다. 진단은 별도 FD에
쓸 수 있게 했으며 성능 비교에서는 비활성화했다.

ARM64 API35 redroid, 같은 app UID·Debian rootfs·ext4에서 기존 제품 엔진
9edb5f...와 후보 a25b41.../DSO 119b88...를 5쌍 교대 비교했다. 기존은
link2symlink, 후보는 native links와 stat/path/mutation/fstat 진입 가속,
경로 조회 DSO와 fastfd를 사용한다. notification/kernel-pipe는 비활성이다.
1,000개 파일, 연속 I/O는 64MiB이며 warm-cache다. 준비·내용 검증·정리는
측정 밖이다. 값은 각 5회의 중앙값이다.

| 작업 | 기존 ms | 후보 ms | 기존 대비 배속 |
| --- | ---: | ---: | ---: |
| 메타데이터 | 67.010 | 52.188 | 1.28 |
| 파일 변경 | 371.236 | 290.551 | 1.28 |
| Python import | 197.852 | 182.868 | 1.08 |
| tar 추출 | 898.920 | 743.690 | 1.21 |
| 작은 파일 읽기 | 183.543 | 103.685 | 1.77 |
| 연속 읽기 | 13.240 | 13.291 | 1.00 |
| 연속 쓰기 | 13.754 | 14.027 | 0.98 |
| fsync 포함 쓰기 | 280.809 | 285.455 | 0.98 |

[80개 원시 결과와 해시](../tools/rootless/faststat/results/fastfd-candidate-2026-09-16.json).
메타데이터는 과거 진입 가속만 쓴 약 25~31ms보다 나빠졌다. 서로 다른 실행
시점의 수치라 원인을 단정하지 않지만, 경로 조회 DSO와 fastfd의 기능을
분리해 비교할 필요가 있다. 작은 파일 읽기는 개선됐지만 순차 I/O는 개선되지
않았다. 이 후보를 기본 제품에 채택하지 않았다. Android 실기기 일반 환경이나
F2FS 검증으로 확대 해석하지 않는다.

JDK 17의 정규 Android 빌드·runtime 단위 시험·image 시험과 instrumentation
빌드가 통과했다. Gradle 캐시 권한 오류는 기존 캐시 쓰기 허용으로 해결했다.
Android MCP로 설치·실행·정리를 확인했으며 incremental 미지원은 streamed로
해결했다. UI/외부 API/외부 앱 변경이 없어 다른 MCP는 사용하지 않았다.
다음은 경로 DSO와 fastfd 분리 및 남은 open/close 비용 개선이다.
**전체 2배 및 파일 I/O 성능 목표는 계속 미달성이다.**

## 22단계: 경로 DSO 분리로 메타데이터 회귀 해결

guest PROOT_FASTSTAT_PATH=0 옵션을 추가했다. 경로 조회는 tracer의 진입
가속에 맡기고 DSO는 열린 FD의 fstat만 가속할 수 있다. fastfd 설치 실험은
이 조합을 쓰도록 변경하고, 일반 설치에 옵션이 새지 않는 단위 시험을 보강했다.

같은 엔진·DSO에서 옵션만 바꾼 3쌍 교대 비교 결과, 메타데이터 중앙값은
51.778→25.851ms, 작은 파일 읽기는 108.564→106.502ms였다. 메타데이터
회귀를 해소하면서 FD 가속을 유지했다. 경로 off/on 기능 회귀와 FD 회귀 모두
통과했다. [12개 원시 결과](../tools/rootless/faststat/results/fastfd-path-split-2026-09-16.json)에
조건과 해시를 기록했다. 전체 8개 작업 비교는 반복하지 않았다.

FD 전용 구성의 새 Debian 설치·기본 환경·하드링크·실제 가속 fstat 확인은
89.669초에 통과했다. JDK 17 정규 Android/runtime/image 검증 및 설치 시험 APK
빌드가 통과했다. Android MCP 설치의 incremental 미지원은 streamed로 해결했다.
UI/외부 API/외부 앱 변경이 없어 다른 MCP는 사용하지 않았다.
기본 제품 엔진은 유지했다. 다음 병목은 파일 열기·닫기이며 close 추적에 연결된
auxv/netlink 상태 정리를 먼저 해결해야 한다.
**전체 2배 및 파일 I/O 성능 목표는 계속 미달성이다.**

## 23단계: close의 상태 정리를 유지하는 알림 경로 실험

close를 무조건 통과시키는 기존 상한 탐색과 달리 auxv/netlink 상태 정리를
유지하는 알림 경로를 구현했다. 커널의 close 실행을 요청하고 응답 성공 후
추적 상태를 지운다. 파이프는 커널 동작을 요구한다. open 알림과 독립적으로
선택할 수 있으며 기본값은 비활성이다.

Android MCP에서 auxv/netlink FD 재사용, 8스레드/800개 파일, guest seccomp
거부, 파이프·조기 종료·프로세스 치환, 임시 dpkg 설치·제거가 통과했다.
open+close 알림과 fastfd의 조합도 기능 회귀를 통과했다. 빌드·서명·설치 통과,
incremental 설치 미지원은 streamed로 해결했다. Context7의 일반 seccomp
자료와 Linux 6.17 원문으로 API 동작을 확인했다. UI/외부 앱 MCP는 사용하지 않았다.

다만 같은 엔진의 off/on 각 1회에서 작은 파일 읽기 97.280→99.779ms,
tar 689.313→705.932ms로 빨라지지 않았다. 반복 비교의 확정 결과는 아니지만
채택 근거도 없으므로 실험 경로로만 남겼다.
[조건과 원시 결과](../tools/rootless/notify/results/close-transport-2026-09-16.json).
전체 비교·새 배포판 설치·Android Gradle은 반복하지 않았다.
다음은 알림 전환 자체의 비용과 닫기 추적을 제거할 수 있는 FD 상태 설계다.
**전체 2배 및 파일 I/O 성능 목표는 계속 미달성이다.**

## 24단계: 알림 동기 깨우기 검증과 방향 전환

커널의 동기 깨우기 옵션을 명시적으로 선택할 수 있게 추가했다. 지원되지
않으면 기존 깨우기를 유지하고 진단에 상태를 남긴다. Android MCP에서 활성
상태와 close 알림 1,060회를 관측했고 FD 재사용·8스레드·guest seccomp·
파이프·임시 dpkg 설치/제거 검사가 통과했다. 빌드·APK 설치도 통과했다.
Context7의 정보가 부족해 Linux 6.17 원문으로 ioctl 인자와 적용 범위를 확인했다.

그러나 각 1회 확인에서 작은 파일 읽기 99.349→106.020ms, tar 추출
703.870→787.940ms로 이득이 없었다. [조건과 결과](../tools/rootless/notify/results/sync-wake-2026-09-16.json).
기본 후보에 채택하지 않았으며 전체 비교·새 설치·Gradle은 반복하지 않았다.
구형 커널 미지원 복귀는 미검증이다. incremental 설치 미지원은 streamed로
해결했고 UI/외부 앱 MCP는 필요하지 않았다.

알림 방식의 미세 조정은 중단하고, 다음은 경로 변환 정보를 공유해 일반 파일
open의 프로세스 왕복 자체를 줄이는 방향이다. 파일 내용이나 inode 결과의
장기 캐시로 대체하지 않고, bind/chroot/rename 변화에 따른 무효화가 필요하다.
**전체 2배 및 파일 I/O 성능 목표는 계속 미달성이다.**

## 25단계: 공유 경로 규칙과 변경 무효화

파일 open의 반복 경로 변환을 줄이기 위한 공유 규칙 게시를 구현했다.
bind 변경 전에 연결된 페이지를 무효화하고 tracee 재개 전에 최신 namespace
규칙을 게시한다. 파일 정보는 캐시하지 않는다. 현재는 진단 조회만 연결했으며
실제 open 가속은 아직 구현하지 않았다. 따라서 성능 비교는 실행하지 않았다.

Android MCP에서 bind 추가·제거, 다른 스레드의 갱신, fork/chroot, 잘못된
경로·출력 버퍼 검사와 기존 FD 회귀(hits=653)가 통과했다. chroot 시험에서
기존 코드의 초기화되지 않은 경로 버퍼 비교로 중단되는 오류를 발견해 수정했다.
엔진/DSO 빌드 및 시험 APK 설치가 통과했다. Gradle과 새 설치는 반복하지 않았다.
자세한 ABI 변경·적용 범위·검증 해시는
[실험 문서](../tools/rootless/faststat/README.md#공유-경로-규칙-게시-기반)에 있다.

다음은 이 규칙을 읽기 전용 일반 파일 open에 연결하고, 링크 및 동시 규칙 변경
시 기존 처리로 복귀하도록 하는 작업이다. **전체 2배 및 파일 I/O 목표는 미달성이다.**

## 26단계: 공유 규칙을 이용한 읽기 전용 파일 열기

root binding의 절대 경로에 프로세스 내부 open을 연결했다. 커널에서 링크 없이
대상을 고정하고 일반 파일/디렉터리임을 확인한 뒤 열며, 공유 규칙 변경 시 기존
처리로 복귀한다. 상대 경로·별도 bind·쓰기·특수 파일은 기존 처리를 사용한다.

Android MCP에서 내용 변경·rename·링크·FIFO·오류·8스레드·fork·seccomp 검사,
기존 FD 회귀, bind/chroot 규칙 갱신, FD 전달 및 스레드 취소 검사가 통과했다.
ARM64 엔진/DSO와 시험 APK 빌드·설치도 통과했다. 세부 적용 범위와 해시는
[실험 문서](../tools/rootless/faststat/README.md#읽기-전용-open-연결)에 기록했다.

기능 검증 후 작은 파일 읽기만 off/on 한 쌍으로 비교했다. 1,000개 읽기는
106.093→86.956ms로 약 18% 줄었지만 guest CPU는 53.287→57.848ms로 늘었다.
단일 비교라 확정 성능으로 사용하지 않는다. 전체 벤치마크와 새 설치는 아직
수행하지 않았다. 다음은 새 Debian 설치 검증과 상대 경로/별도 bind 적용 확장이다.
**전체 2배 및 파일 I/O 목표의 달성은 아직 입증되지 않았다.**

## 27단계: 읽기 전용 open 후보의 새 Debian 설치 검증

설치 실행기에 명시적 fastOpen 옵션을 연결하고, 설치 시험에 가속 open 사용
확인을 추가했다. host/guest 환경 분리와 DSO 필수·notification 병용 거부를
단위 시험으로 검사한다. 기본 설치 경로에는 옵션을 켜지 않았다.

2026-09-17 Android MCP에서 새 Debian 설치·기본 환경·하드링크·가속 open/fstat
확인 시험이 88.558초에 통과했다. 시험 후 임시 설치 디렉터리는 0개였다.
엔진은 26단계와 같고 DSO에는 open_hits 종료 진단을 추가했다.
JDK 17 정규 빌드·runtime 단위 시험·image 시험·instrumentation APK 빌드 및
DSO 빌드도 통과했다. incremental 설치 미지원은 streamed 설치로 해결했다.
전체 성능 비교는 반복하지 않았다. 설치 시간은 다운로드를 포함하며 성능
개선 수치로 사용하지 않는다. 적용 범위 확장과 최종 대표 작업 비교가 남아 있다.
**전체 2배 및 파일 I/O 목표의 달성은 아직 입증되지 않았다.**

## 28단계: 최상위 bind 영역의 읽기 전용 open

공유 규칙 기반 열기를 `/workspace` 같은 최상위 bind까지 확장했다. 열린
대상의 파일시스템 종류를 매번 확인해 일반 저장소만 가속하며, procfs 등은
기존 처리로 복귀한다. 중첩 bind·상대 경로·FUSE는 아직 기존 처리다.

Android MCP에서 bind 파일의 가속 사용, umount 뒤 ENOENT, 다른 이름에 연결한
procfs의 auxv 처리 유지, fstatfs 거부 시 복귀와 기존 open/FD/syscall 회귀가
통과했다. DSO 빌드와 시험 APK 설치도 통과했다. 성능 비교·새 설치·Gradle은
반복하지 않았다. 자세한 적용 범위와 검증 해시는
[실험 문서](../tools/rootless/faststat/README.md#최상위-bind-파일-열기)에 있다.
**전체 2배 및 파일 I/O 목표의 달성은 아직 입증되지 않았다.**

## 29단계: fastopen 후보와 원본의 대표 작업 비교

2026-09-17 동일 Debian/app UID/ext4에서 원본과 누적 후보를 3쌍 교대로 비교했다.
파일 수는 1,000개, 순차 전송은 64 MiB, warm cache 조건이다. 준비·검증·정리는
측정 밖에서 수행했고 48개 실행 결과의 작업 검증이 모두 통과했다. 원본은
--link2symlink/DSO 없음, 후보는 native links + 진입 가속 + fastfd/fastpath/fastopen,
경로 DSO off다. notification/kernel-pipe/진단은 껐다.

| 작업 | 원본 중앙값 ms | 후보 중앙값 ms | 배속 |
| --- | ---: | ---: | ---: |
| 메타데이터 조회 | 65.925 | 30.777 | 2.14 |
| 생성·rename·삭제 | 374.682 | 298.898 | 1.25 |
| Python import | 205.023 | 177.055 | 1.16 |
| tar 추출 | 908.652 | 704.167 | 1.29 |
| 작은 파일 읽기 | 183.911 | 86.390 | 2.13 |
| 순차 읽기 | 13.249 | 13.609 | 0.97 |
| 순차 쓰기 | 14.046 | 13.947 | 1.01 |
| fsync 포함 쓰기 | 276.069 | 275.155 | 1.00 |

[조건·해시·범위·48개 원시 결과](../tools/rootless/faststat/results/fastopen-candidate-2026-09-17.json).
메타데이터와 작은 파일 읽기는 이 조건에서 2배를 넘었다. 전체 작업이나 실제
휴대폰/F2FS에서의 2배를 의미하지 않는다. 순차 I/O 개선은 입증되지 않았다.
다음 우선순위는 상대 경로 읽기의 확장보다 생성·쓰기 작업의 tracer 비용 감소다.
이들 작업은 현재 fastopen에서 기존 libc/PRoot로 복귀하며, mutations 시험은
write_bytes → rename → unlink, tar는 파일 생성/쓰기를 포함한다.

Android MCP로 실행했고 별도 비교 APK의 서명·설치가 통과했다. incremental
미지원은 streamed 설치로 해결했다. 코드 변경이 없어 Gradle 및 새 설치는
반복하지 않았다. UI·새 외부 API·외부 앱 관련 MCP도 사용하지 않았다.
**메타데이터와 작은 파일 읽기의 2배는 관측됐지만 전체 목표는 미달성이다.**

## 30단계: unlink 진입 실행 실험 기각

생성·rename·삭제 작업의 후속 후보로 일반 파일 unlinkat을 tracer 진입에서
직접 실행하는 변경을 시험했다. ARM64 빌드와 Android MCP 기능 검증은 통과했다.
열린 inode/하드링크·dirfd·링크·디렉터리·잘못된 flags/포인터·guest seccomp
거부를 확인했고 켬/끔 모두 정상 동작했다.

그러나 동일한 파일 생성·삭제 100회 작업에서 계측 결과는 다음과 같았다.

| 카운터 | 진입 실행 끔 | 진입 실행 켬 |
| --- | ---: | ---: |
| enter_stop | 839 | 841 |
| exit_stop | 149 | 152 |
| read_string | 521 | 622 |
| vm_read | 672 | 772 |

반환 정지가 줄지 않았고 경로 읽기만 약 100회 추가됐다. 이 경로에서 줄일 수
있다고 가정한 반환 정지가 관측되지 않았으므로 구현을 채택하지 않고 제거했다.
시간 벤치마크를 반복하지 않았다. 새 삭제 회귀 시험 `unlink-entry-test.py`는
남겼다. 현재 누적 후보는 29단계와 같으며 다음은 파일 생성·쓰기 open 비용이다.

실험 엔진 해시는 345e76e378da9c31fbd200148caddf2b1b54d34ad84cc788a675bc3c07477272다.
이는 채택된 엔진이 아니다. APK incremental 미지원은 streamed 설치로 해결했다.
Gradle·새 Debian 설치·UI 검증은 이번 실험 범위에 없어 실행하지 않았다.
**전체 2배 및 파일 I/O 목표는 여전히 미달성이다.**

## 31단계: 새 일반 파일 생성의 프로세스 내부 처리

명시적 PROOT_FASTCREATE 옵션으로 O_CREAT 쓰기 열기의 새 파일 생성을 연결했다.
고정한 부모 디렉터리에서 배타적으로 생성하며, 기존 파일은 libc/PRoot로 복귀한다.
성공한 생성은 재시도하지 않는다. 아직 기본 후보 설치 옵션에는 연결하지 않았다.

Android MCP에서 생성 가속·umask·기존 파일 truncate·symlink append·8스레드
배타적 생성 경합·guest seccomp와 기존 읽기 회귀가 통과했다. DSO 빌드 및 시험
APK 설치도 통과했다. [적용 조건과 해시](../tools/rootless/faststat/README.md#새-일반-파일-생성-실험).

영향받는 두 작업만 off/on 한 쌍으로 확인했다. 생성·rename·삭제는
297.021→279.954ms, tar 추출은 706.177→697.407ms였으며 CPU 시간은 증가했다.
이는 단일 비교로 확정 개선 수치가 아니다. 새 생성 옵션의 Debian 새 설치 및
전체 대표 작업 검증은 남아 있다. 읽기 쪽 2배 결과를 생성·쓰기 전체에 적용할 수
없다. **전체 2배 및 파일 I/O 목표의 달성은 아직 입증되지 않았다.**

## 32단계: 생성 가속 후보의 새 Debian 설치 검증

기본 off인 fastCreate 설치 옵션과 instrumentation 인자를 연결했다. fastOpen
의존 조건 및 기본 비활성 동작을 단위 시험으로 검사했다. 설치 후 Python으로
파일을 생성해 create_hits 양수를 요구하고 기존 open/fstat 확인도 유지했다.

2026-09-17 Android MCP에서 새 Debian 설치·환경·하드링크·가속 호출 확인이
86.911초에 통과했다. 임시 설치 디렉터리는 시험 후 0개였다. JDK 17 정규 Android
빌드·runtime 단위 시험·image 시험·instrumentation APK와 DSO 빌드도 통과했다.
APK incremental 미지원은 streamed로 해결했다. [조건과 DSO 해시](../tools/rootless/faststat/README.md#생성-가속의-새-설치-시험-연결).

성능 비교는 반복하지 않았으며 설치 시간을 성능 향상으로 해석하지 않는다.
생성 가속을 포함한 전체 2배 달성은 아직 입증되지 않았다. 일반 세션에 후보를
채택하기 위한 적용 범위·비용 개선과 최종 대표 작업 검증이 남아 있다.

## 33단계: 삭제의 tracer 진입 제거

30단계는 tracer 안에서 삭제를 대신 수행해 이득이 없었다. 이번에는 공유 경로
규칙과 고정한 부모 디렉터리를 사용해 guest DSO에서 unlinkat을 직접 실행한다.
성공은 재시도하지 않고 오류·지원하지 않는 경로는 기존 처리로 복귀한다.
명시적 PROOT_FASTUNLINK 옵션이며 제품 기본 구성은 바꾸지 않았다.

Android MCP의 삭제·생성·읽기 기능 회귀가 모두 통과했다. 파일 생성·삭제 100회
계측에서 enter_stop=610→510, exit_stop=141→141로 진입 100회 제거를 확인했다.
read_string도 358→258로 줄었다. 시간 비교를 반복하지 않았으며 아직 배속 개선
수치는 주장하지 않는다. [조건·해시·범위](../tools/rootless/faststat/README.md#게스트-내부-unlink-실험).

엔진/DSO 빌드와 시험 APK 설치가 통과했다. 새 삭제 옵션의 Debian 설치 및 누적
성능 검증은 남아 있다. **전체 2배 및 파일 I/O 목표는 아직 미달성이다.**

## 34단계: 상대 경로 가속과 cwd 무효화

공유 규칙에 PRoot의 가상 cwd를 추가하고 open/create/unlink의 AT_FDCWD 상대
경로를 연결했다. chdir/fchdir 및 cwd rename 시 다른 스레드의 규칙도 무효화한다.
실제 디렉터리 FD와 `.`/`..` 정규화가 필요한 경로는 기존 처리로 복귀한다.
실험 공유 ABI는 버전 3이며 엔진과 DSO를 함께 다시 빌드해야 한다.

Android MCP에서 상대 경로 가속, cwd 변경·실패·스레드 공유·rename·fork 분리와
기존 bind/chroot/생성/읽기/삭제/FD 회귀가 통과했다. 엔진/DSO 빌드·APK 설치도
통과했다. [상세 범위와 해시](../tools/rootless/faststat/README.md#가상-작업-디렉터리와-상대-경로).
성능 비교·Gradle·새 설치는 반복하지 않았다. 새 ABI의 설치와 누적 성능 검증은
남아 있다. **전체 2배 및 파일 I/O 목표의 달성은 아직 입증되지 않았다.**

## 35단계: 상대 경로·삭제 누적 후보의 새 Debian 설치

설치 실행기의 명시적 fastUnlink 옵션과 instrumentation 인자를 연결했다.
설치 후 상대 경로 생성·내용 확인·삭제와 create/open/unlink/fstat 가속 진단을
각각 확인한다. 옵션 의존 조건과 기본 비활성은 단위 시험으로 검증했다.

2026-09-17 Android MCP에서 새 Debian 설치 및 위 검증이 88.025초에 통과했고,
임시 설치 디렉터리는 0개였다. JDK 17 정규 빌드·runtime 단위 시험·image 시험·
instrumentation APK 및 DSO 빌드도 통과했다. [해시·조건](../tools/rootless/faststat/README.md#삭제상대-경로-후보의-새-설치-시험-연결).
APK incremental 미지원은 streamed로 해결했다. 이번에는 성능 비교를 하지 않았다.

이는 누적 후보의 설치 정상 동작 증거이며 성능 2배 또는 제품 기본 적용을 뜻하지
않는다. **전체 작업 2배 및 파일 I/O 목표는 아직 미달성이다.**

## 36단계: 흔한 dot/slash 표기의 가속 누락 축소

공유 경로를 사용하는 open/create/unlink에서 `./file`과 중복 slash를 처리한다.
`file/.`의 디렉터리 요구 조건을 보존하며 `..`는 기존 처리에 맡긴다.
Android MCP에서 해당 표기의 실제 가속 사용 및 ENOTDIR 유지, 기존 cwd·생성·
읽기·삭제 회귀를 통과했다. DSO 빌드·시험 APK 설치도 통과했다.
[범위와 해시](../tools/rootless/faststat/README.md#dot와-중복-slash-표기).

성능 비교·새 설치·Gradle은 반복하지 않았다. 단순 경로 표기 때문에 적용되지
않던 가속 범위를 넓힌 변경이며 추가 배속 수치는 아직 없다.
**전체 작업 2배 및 파일 I/O 목표는 여전히 미달성이다.**

## 37단계: 터미널 여부 조회의 불필요한 왕복 제거

syscall별 계측을 추가해 남은 추적을 확인했다. 생성·삭제 실행에서 ioctl 진입/
반환이 2,066회, tar 추출에서는 4,072회였다. PRoot가 수정 없이 전달하는 TCGETS만
직접 커널에 전달하는 명시적 PROOT_TCGETS_DIRECT 옵션을 추가했다.

Android MCP에서 실제 PTY, 파일·파이프, 오류 포인터/FD, termios 설정 후 조회,
guest seccomp 거부를 off/on 모두 검사해 통과했다. 두 작업의 ioctl 추적은 각각
7회만 남았다. 총 반환 정지는 2,176→119회, 7,329→3,266회로 줄었다. 각 syscall
합계와 총계도 일치했다. 이 계측은 준비·정리를 포함하며 시간 배속 증거는 아니다.
[범위·원시 결과·해시](../tools/rootless/faststat/README.md#syscall별-계측과-tcgets-직접-전달).

엔진 빌드와 시험 APK 설치가 통과했다. 새 옵션의 설치/시간 검증과 제품 적용은
남아 있으며, **전체 작업 2배 및 파일 I/O 목표는 아직 입증되지 않았다.**

## 38단계: 생성·삭제·상대 경로·TCGETS 누적 비교

2026-09-17 원본과 누적 후보를 3쌍 교대로 비교했다. 동일 API35 ARM64 redroid,
Debian/app UID/ext4, warm cache, 1,000개 파일과 64 MiB 순차 전송 조건이다.
계측과 종료 진단을 끄고 48개 실행의 작업 검증을 모두 통과했다.

| 작업 | 원본 중앙값 ms | 후보 중앙값 ms | 배속 |
| --- | ---: | ---: | ---: |
| 메타데이터 조회 | 66.787 | 30.810 | 2.17 |
| 생성·rename·삭제 | 366.816 | 234.282 | 1.57 |
| Python import | 200.422 | 164.150 | 1.22 |
| tar 추출 | 895.604 | 674.690 | 1.33 |
| 작은 파일 읽기 | 182.418 | 57.842 | 3.15 |
| 순차 읽기 | 13.057 | 13.429 | 0.97 |
| 순차 쓰기 | 13.712 | 13.905 | 0.99 |
| fsync 포함 쓰기 | 288.837 | 288.472 | 1.00 |

[조건·해시·각 표본·48개 원시 결과](../tools/rootless/faststat/results/cwd-tcgets-candidate-2026-09-17.json).
작은 파일 읽기는 원본 대비 3배를 넘었고 생성·삭제 혼합 작업도 개선됐지만 전체
작업 2배는 달성하지 못했다. 순차 I/O 개선은 관측되지 않았다. 이 결과를 실제
휴대폰/F2FS 또는 cold cache로 일반화하지 않는다. 메타데이터 후보 표본은
25.741~38.219ms로 변동이 있어 2.17배를 모든 실행의 보장치로 해석하지 않는다.

Android MCP로 실행했고 비교 APK 서명·설치가 통과했다. incremental 미지원은
streamed로 해결했다. 코드 변경이 없어 Gradle은 반복하지 않았다. 새 TCGETS 옵션을
포함한 새 설치 검증은 아직 남아 있다. 다음은 tar의 geteuid/fchmodat/fchownat처럼
반환 정지가 남은 경로다. 카운터 횟수만으로 각 경로의 시간 비중을 단정하지 않는다.
**전체 작업 2배 및 파일 I/O 목표는 아직 미달성이다.**

## 39단계: ID 조회를 진입 시점에 완료

tar에서 반복되는 ID 조회의 반환 왕복을 줄이기 위해 실험 옵션
PROOT_ID_ENTRY를 추가했다. 현재 fake_id0 상태에서 real/effective UID/GID를
읽어 진입 시점에 완료하며 별도 ID 캐시를 두지 않는다.

Android MCP에서 off/on 모두 ID 변경·8개 스레드·fork 격리·seccomp 거부 검사를
통과했고 누적 가속의 FD 회귀도 통과했다. geteuid 1,000회 루프를 포함한 전체
실행에서 해당 호출의 반환 정지는 1,001→0회, 전체 반환 정지는 1,070→66회였다.
진입 정지는 그대로이므로 syscall 추적 전체를 없앤 것은 아니다.
[구현 범위·검증·엔진 해시](../tools/rootless/faststat/README.md#id-조회의-반환-정지-제거).

엔진 빌드와 Python 구문 검사가 통과했다. 전체 시간 비교·새 설치·Gradle은
반복하지 않았다. 새 ID/TCGETS 옵션의 설치 통합과 제품 적용은 남아 있다.
**전체 작업 2배 및 파일 I/O 목표는 아직 미달성이다.**

## 40단계: ID·TCGETS를 포함한 새 설치 통합 검증

설치 실행기에 두 옵션을 연결하고 단위 시험으로 host 전달 및 기본 비활성을
검사했다. 최신 실험 엔진과 DSO의 해시를 확인한 별도 시험 rootfs에서 누적
가속을 모두 켜고 새 Debian 설치가 85.842초에 통과했다. 공식 패키지·기본 환경,
하드링크, 상대 경로 파일 내용과 가속 create/open/unlink/fstat 사용을 검증했다.
[설정과 검증 범위](../tools/rootless/faststat/README.md#idtcgets-누적-후보의-새-debian-설치).

정규 Android 빌드·runtime 단위 시험·image 시험과 instrumentation APK 빌드가
통과했다. Android MCP에서 실행했으며 전체 성능 비교는 반복하지 않았다.
설치 성공을 전체 성능 2배 달성으로 해석하지 않는다. 제품 기본 적용과
남은 생성·변경/추출 비용 개선이 필요하다. **전체 목표는 아직 미달성이다.**

## 41단계: 일반 파일 권한·소유자 변경의 반환 왕복 제거 실험

tar 추출에서 반복되는 fchmodat/fchownat를 진입 시점에 완료하는 기본 비활성
실험 옵션을 추가했다. 기존 경로 번역과 ID 변환을 사용하며 실제 파일 변경을
수행한다. Android MCP에서 off/on 모두 권한·소유자·내용·오류·seccomp 검사가
통과했다. 각각 100회 호출의 반환 정지는 합계 200→0회로 줄었지만 추가 경로
읽기가 200회 발생했다. 따라서 시간 이득은 아직 입증하지 않았다.
[구현 조건·해시·검증 범위](../tools/rootless/faststat/README.md#일반-파일-권한소유자-변경의-진입-완료-실험).

엔진 빌드와 시험 APK 설치가 통과했다. 전체 비교와 새 설치는 반복하지 않았다.
후속 누적 비교에서 비용 감소를 확인해야 하며 **전체 2배 목표는 미달성이다.**

## 42단계: 권한 변경 가속의 중복 경로 읽기 제거

같은 syscall 안에서 이미 번역한 경로를 재사용해 직전 후보의 추가 경로 읽기를
제거했다. 경로 포인터가 확장 처리 중 바뀌면 가속하지 않는다. dirfd 상대 경로와
빈 경로 시험을 추가해 Android MCP에서 off/on 모두 통과했다.

chmod/chown 각 100회 진단에서 반환 정지는 총 200회 줄었고 read_string은
off/on 모두 528회였다. 엔진 빌드와 시험 APK 설치가 통과했다.
[범위·해시·결과](../tools/rootless/faststat/README.md#권한-변경-경로의-재독-제거).
전체 시간 비교와 새 설치는 반복하지 않았다. **전체 2배 목표는 미달성이다.**

## 43단계: ID·권한 개선의 tar 한정 효과 확인

동일 엔진·누적 가속에서 옵션 조합을 순환 순서로 각 3회 실행했다. 9개 실행의
파일 내용 검사가 통과했다. 중앙값은 둘 다 off 679.612ms, ID만 on 660.439ms,
둘 다 on 656.542ms였다. 권한 옵션의 추가 감소는 약 0.6%이며 표본이 겹쳐
실질적 벽시계 이득은 입증하지 못했다. guest CPU 감소를 tracer 포함 전체 CPU
감소로 해석하지 않는다. 권한 옵션은 계속 실험 상태이며 기본 적용 근거가 아니다.
[원시 결과와 범위](../tools/rootless/faststat/results/permission-id-tar-2026-09-17.json).

전체 벤치마크 대신 tar만 확인했다. 새 설치와 빌드는 반복하지 않았다.
남은 격차는 이 작은 반환 왕복 개선만으로 해결되지 않았다. **전체 목표는 미달성이다.**

## 44단계: stat의 경로 번역 요청 제거

공유 cwd/bind 정보로 일반 파일·디렉터리의 stat 계열 조회를 처리하는 실험을
추가했다. 매번 커널에서 메타데이터를 읽으며 resolve prctl 요청이 필요 없다.
원래 newfstatat 번호를 사용해 guest seccomp 거부를 유지한다. 실패와 지원하지
않는 경로는 원래 처리로 복귀한다.

Android MCP에서 실제 가속 hits와 신선한 파일 크기·ID·삭제·오류·seccomp를
검증했고 FD·cwd·bind/chroot 회귀도 통과했다. 100회 stat 루프를 포함한 전체
실행의 진입 추적이 517→299회로 줄었고 prctl은 늘지 않았다.
[범위·설정·해시](../tools/rootless/faststat/README.md#공유-경로-기반-stat).
엔진·DSO 빌드가 통과했다. 새 설치와 시간 비교·제품 적용은 남아 있으며
**전체 2배 목표는 미달성이다.**

## 45단계: 공유 stat의 설치 통합 검증

설치 실행기에 공유 stat 옵션을 연결하고 host/guest 설정 분리 및 옵션 의존성을
단위 시험으로 확인했다. Android MCP에서 최신 엔진·DSO로 새 Debian 설치가
85.992초에 통과했다. 공식 패키지 구성·기본 환경·하드링크·파일 내용과 실제
stat/fstat/open/create/unlink 가속 사용을 확인했다. 권한 변경 실험은 꺼 두었다.
[설정·검증 범위](../tools/rootless/faststat/README.md#공유-stat를-포함한-debian-설치).

정규 빌드·runtime 단위 시험·image 시험·instrumentation APK 빌드가 통과했다.
설치 시간은 성능 향상 수치로 해석하지 않는다. 누적 성능 확인과 제품 적용이
남아 있으며 **전체 목표는 미달성이다.**
