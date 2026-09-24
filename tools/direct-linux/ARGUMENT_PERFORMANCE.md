# Direct Linux 인자 전달 비용 개선

2026-09-24. 인자가 많은 외부 명령의 시작 비용을 줄였다. 동일한 96개 인자를
전달하는 `/bin/true` 30회에서 Direct Linux의 중앙값은 **9.3–12.1% 감소**했다.
최적화 전에는 같은 실행의 가속 Rootless보다 11.8–12.2% 느렸고, 이후에는
차이가 0.4% 이내였다. 짧은 명령이나 전체 Python/Git 작업의 일반적인 성능
우위는 입증하지 않았다. 기존 Rootless와 제품 런타임 선택은 변경하지 않았다.

## 원인과 변경

PRoot 추적기를 제거해도 Direct의 사용자 공간 `exec` 구현 비용은 남는다.
이전 [ELF 개선](COMMAND_PERFORMANCE.md)은 실행 파일 전체의 중복 읽기·비교를
제거했다. 이번에는 `exec.c`의 `vector`와 `stack_string`이 같은 페이지에 있는
포인터·문자열도 매번 `getpid`와 `process_vm_readv`로 읽는 비용을 확인했다.

인자 수에 따라 반복되던 읽기를 한 벡터 복사 안에서만 재사용한다. 포인터 표와
문자열에 각각 한 페이지의 임시 스냅샷을 둔다. 다른 페이지가 필요하면 교체하고,
argv와 envp 사이 및 다음 exec에서는 새로 읽는다. ELF·경로·파일 내용의 영속
캐시는 아니다. 변경될 수 있는 내부 shebang 문자열은 이 스냅샷을 사용하지 않는다.
추가 버퍼는 실제 호스트 페이지 두 개이며, exec 준비 메모리와 함께 해제한다.

128개의 동일 페이지 문자열을 복사하는 호스트 검사에서 읽기 호출은 기존
알고리즘의 257회에서 2회가 된다. 실제 런타임에서는 각각의 읽기가 위의 두
시스템 호출을 사용한다. 주소 오류 검사는 계속 커널의 자기 프로세스 메모리
읽기를 사용한다. 128개 인자·256개 환경변수·문자열당 128 KiB 제한과 exec의
이미지 교체 전 오류 반환을 유지한다. 페이지 경계에서 읽기를 나누며, 읽을 수
없는 페이지 너머에서 종료 NUL을 찾지 않는다. 동시 변경에 대한 원자적 전체
스냅샷을 보장하지 않으며 전체 Linux exec 의미가 완성된 것도 아니다.

처음에는 슬롯당 최대 페이지 크기인 64 KiB를 예약했다. 인자 복사 구간은
49.268→44.780 µs로 줄었지만 임시 메모리 해제는 10.371→17.943 µs로 늘었고,
짧은 명령 전체 측정에서도 이점이 없었다. 이 버전은 채택하지 않았다.
실제 페이지 크기로 줄인 최종 버전의 해당 구간은 각각 43.855 µs와 11.451 µs였다.
프로파일은 짧은 명령 30개의 평균이며 아래 처리 시간 측정과 별도로 실행했다.

## 같은 입력으로 측정한 결과

[원시 진단·시간 표본](results/argument-performance-2026-09-24.json)에 중간 버전도
기록했다. 최종 비교 네 번은 동일한 716개 fixture와 같은 Rootless 바이너리를
사용했다. 각 실행마다 순서를 번갈아 바꾼 7쌍을 측정했고, 이전 APK와 새 APK로
다시 교체해 비교를 반복했다. 추적·프로파일 로그는 측정 중 껐다.
게스트 CLOCK_MONOTONIC으로 셸 생성, 외부 명령 30회, 종료 대기까지 측정하며,
Android 바깥 실행기의 시작·관찰 지연은 포함하지 않는다. warm 작업이다.

| 인자 96개 × 명령 30회 | Direct 중앙값 | Rootless 중앙값 |
| --- | ---: | ---: |
| 변경 전 | 54.514 ms | 48.566 ms |
| 변경 후 | 49.449 ms | 49.490 ms |
| 변경 전, 반복 | 54.123 ms | 48.420 ms |
| 변경 후, 반복 | 47.586 ms | 47.399 ms |

Rootless 자체 시간도 달라졌으므로 전후 차이 전체를 코드 효과라고 단정하지 않는다.
두 비교 모두 인자 수가 많을 때의 격차가 줄었다. 추가 인자가 없는 명령 30회는
48.089→47.960 ms와 47.402→46.583 ms였다. 작은 차이는 뚜렷한 개선으로 해석하지
않는다. Python/Git 전체 작업은 563.291→559.654 ms와 547.340→556.598 ms로,
일관된 추가 개선이 없었다. Rootless의 기존 Git 정리 실패 때문에 전체 앱 비교는
명시적으로 제외했다. 파일 stat/open 측정에서는 기존 가속 preload 적중을 확인했다.

CPU·메모리 사용량, cold cache, 실제 기기의 열·전원 영향은 측정하지 않았다.
7개 표본으로 꼬리 지연이나 전체 명령 성능을 일반화하지 않는다.

## 검증과 재현

`process-test.c`의 `arguments-benchmark`와 Android 테스트의 기존
`directLinuxBenchmark` 옵션에 시나리오를 추가했다. 별도 APK의 빌드·fixture·서명
절차는 [ELF 문서](ELF.md#reproduction)를 따른다. 시간 측정 옵션은
`directLinuxLoader=true`, `directLinuxBenchmark=true`,
`directLinuxSkipApplicationReference=true`다. 프로파일은 별도로
`directLinuxProfile=true`를 사용한다.

최종 로더 SHA-256:
`8997164fcf180ce540d671898d5c9eced3a7b70748f7e6704e828d1fe8f463be`.
비교 기준은 `00e0b73`의 로더
`50b8267e428324736697a69e5406e0c637200caac9518749146c4d795ee5f9f8`이다.
작업 시작 시 존재하던 미완성 스레드 변경은 이번 로더 빌드·커밋에 포함하지 않았다.

통과한 검증:

- `clang -O2 -Wall -Wextra -Werror tools/direct-linux/exec-metadata-test.c tools/direct-linux/memory.c tools/direct-linux/copy.S -o /tmp/direct-linux-exec-metadata-test`와 실행.
  실제 `process_vm_readv`로 잘못된 주소, 보호 페이지, 페이지 경계의 NUL,
  비정렬 포인터 표, argv/envp 제한, 긴 문자열, 벡터 사이 재읽기를 검사했다.
  기존 ELF 메타데이터 변경 거부·원본 inode·페이지 매핑 검사도 통과했다.
- 네이티브 빌드의 경고·미해결 심볼·TLS 검사, 최종 APK 서명 검증.
- JDK 17에서 저장소 표준 `:android-app:assembleDebug :android-runtime:testDebugUnitTest :image:test`와
  `:android-runtime:assembleDebugAndroidTest` 성공. 변경 없는 단위 테스트는
  UP-TO-DATE로 기존 결과를 재사용했으며 새 실행으로 세지 않는다.
- Android MCP로 일반 앱 UID에서 최종 회귀 검사 통과(10.571초): 64개 일반 실행과
  두 PTY 시나리오. 신호·네트워크·DNS/TLS·Unix socket 비교, 정적/동적 glibc의
  긴/공유/빈 인자 및 보호 페이지 끝의 argv 표, 환경변수 전달을 포함한다.
  [최종 결과](results/argument-runtime-2026-09-24.json)에 기록했다.
  최종 correctness fixture는 인자 검증을 추가한 두 process 바이너리만 시간 측정
  fixture와 다르며, 네 번의 전후 시간 비교끼리는 모든 fixture가 같았다.

호스트 첫 실행은 도구 샌드박스의 자기 메모리 읽기 제한 때문에 실패했고,
승인된 실행 환경에서 같은 검사가 통과했다. 초기 시험 APK의 fixture 누락과
마지막 fixture의 파일 크기 메타데이터 누락은 수정 후 재실행했다. 최종 설치는
공간 부족으로 한 번 실패했고 이번 작업의 재생성 가능한 unsigned APK만 정리한
뒤 성공했다. 실패한 실행은 성능·통과 표본에 포함하지 않았다.

Android MCP는 설치·실행·결과 및 SELinux 상태의 근거로 사용했다. 이미 쓰던
메모리 읽기 API만 이용하므로 Context7, UI 변경이 없어 Replicant, 외부 앱 작업이
없어 codex_apps는 사용하지 않았다. privileged AVF 검사는 이 변경의 대상이 아니다.
대상은 ARM64 API 35 redroid, SELinux Disabled, 초기 Seccomp=0/CapEff=0/TracerPid=0이다.
stock enforcing 기기의 Android 정책 호환성은 여전히 미검증이다. 기존 Unix 긴 경로와
Rootless named datagram의 실패도 유지된다. 스레드, 전체 신호·파일시스템·패키지 및
제품 세션 지원 등 일반 호환성 과제는 이 성능 수정으로 해결되지 않는다.
