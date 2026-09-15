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
