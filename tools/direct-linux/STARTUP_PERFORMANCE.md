# Direct Linux: exec 밖의 시작·종료 비용

2026-09-25. 초기 APK 준비에만 사용하는 메모리 매핑을 fork 자식에게 물려주지
않도록 바꿨다. 이전 `6bf6203`과 같은 APK에서 교차 비교한 **54쌍의 짧은 명령
실행 시간 감소율 중앙값은 24.3%**였고, 54쌍 모두 개선됐다. 별도 실험 로더의
warm workload 결과이며, 제품 런타임이나 일반 Linux 성능 우위를 뜻하지 않는다.

## 비용 분리

`startup-test.c`는 같은 CLOCK_MONOTONIC 기준으로 fork 직전, 부모/자식 복귀,
exec 직전, 새 프로그램의 main 진입, 종료 요청 직전, 부모의 reap 완료를 기록한다.
자식은 작은 파이프로 시간을 전달하고, 부모는 reap 이후 JSON을 출력한다.
동적/정적 프로그램 exec 및 exec 없는 fork/exit를 각각 30회 실행한다.

`DIRECT_LINUX_STARTUP_PROFILE=1`은 dispatcher의 exec 진입·반환 시점과 종료
진입 시점을 기록한다. exec 이후 번역한 syscall의 횟수와 처리 시간을 집계하고
종료 때 한 번 출력한다. 허용된 native syscall, SIGSYS 진입·복귀, page fault,
스케줄러 시간은 syscall별 집계에 포함되지 않는다. 따라서 집계의 합을 전체
초기화 시간으로 해석하지 않는다. 단일 스레드 진단용이며 기본값은 꺼짐이다.

별도 진단 실행에서 얻은 명령당 평균은 다음과 같다. 동적 실행 행은 측정용
프로그램이며, 실제 `/bin/true`의 전체 성능은 아래 독립 벤치마크로 확인한다.

| 구간 | 이전 | 개선 |
| --- | ---: | ---: |
| 동적 실행: fork 직전→자식 복귀 | 312.011 µs | 225.030 µs |
| 동적 실행: exec dispatcher 내부 | 597.187 µs | 707.623 µs |
| 동적 실행: exec 반환→main 진입 | 401.365 µs | 375.992 µs |
| 동적 실행: 종료 진입→부모 reap | 286.045 µs | 121.016 µs |
| exec 없는 fork/exit 전체 | 602.107 µs | 326.929 µs |

종료→reap에는 진단 보고서 출력과 스케줄링이 포함된다. 부모의 fork 반환 시간은
자식 경로와 겹치므로 위 구간에 더하지 않는다. 작은 파이프 전송·wrapper 구간도
원시 timestamp에 보존했다. 모든 구간이 개선된 것은 아니며, 서로 다른 진단
실행의 차이를 각 변경의 정확한 기여율로 주장하지 않는다.

실제 셸의 `/bin/true`도 이전 진단에서 exec 반환→종료 진입에 평균 422.953 µs를
썼다. 경로 접근 검사와 라이브러리 열기가 번역 비용에서 두드러졌지만, 짧은
경로 복사, PID 조회 재사용, 읽기 open 단축, seccomp 허용 검사 순서 변경은
전체 짧은 명령에서 뚜렷한 이득을 확인하지 못해 최종 구현에서 제외했다.

## 원인과 구현

이전에는 Bionic 링커·라이브러리·할당자·초기 스택 등의 매핑이 게스트 fork에도
상속됐다. 게스트 진입 뒤 이 런타임은 이미 libc/TLS에 의존하지 않는 독립 코드만
사용하지만, 자식 생성·종료 때 불필요한 VMA와 페이지 테이블까지 처리했다.

`bootstrap.h`는 게스트 이미지와 신호 스택을 만들기 **전에** `/proc/self/maps`를
읽고 초기 준비용 매핑에 [MADV_DONTFORK](https://man7.org/linux/man-pages/man2/madvise.2.html)를
적용한다. 부모의 매핑·내용은 유지되며, 이후 fork 자식에서만 상속이 생략된다.
최종 진단의 셸 준비에서는 116개 매핑 중 108개가 대상이었다. 약 11 GB라는
기록은 희소 예약을 포함한 **가상 주소 범위**이며, 그만큼의 RAM을 확보했다는 뜻이 아니다.

다음 매핑은 보존한다.

- 자신의 ELF PT_LOAD 전체 주소 범위와 겹치는 매핑: 코드, 데이터, BSS, 페이지 패딩.
- vDSO/vvar: 게스트에 제공하는 커널 시계 데이터와 코드.
- 호스트 TLS를 포함한 매핑: 커널의 기존 rseq 등록이 이를 참조할 수 있다.
- 이 처리 이후 새로 만든 모든 게스트·힙·신호 스택 매핑: 기본 상속 동작 유지.

초기 게스트 진입 전 물려받은 신호 핸들러, robust-list, clear-child-TID 포인터도
정리한다. IGN은 보존하고 내부 SIGSYS 핸들러는 이후 설치한다. 호스트 TLS를
제외했던 초기 시험은 glibc 호스트에서 fork 복귀 시 SIGSEGV가 났다. 실제 자식과
손자를 만드는 전용 테스트로 TLS 보존 수정, 런타임/BSS/게스트/신호 스택/vDSO
생존과 부모의 원래 내용 보존을 확인했다.

이 정책은 독립 APK 프로세스가 게스트 진입 후 Bionic으로 돌아오지 않는다는
기존 경계에 의존한다. 향후 게스트 실행 중 Bionic 호출, 새로운 커널 등록 포인터,
VM 공유 방식의 spawn 등을 도입하면 상속 정책을 재검토해야 한다. 현재의
copy-on-write vfork 및 fork 동작은 유지한다. 실제 shared-VM vfork로 바꾸면
in-process exec가 부모 메모리까지 변경할 수 있어 이번 대안으로 사용하지 않았다.

프로그램 LOAD 범위나 maps를 확인할 수 없거나 madvise가 실패하면 초기 준비를
명시적으로 실패시킨다. 초기 준비에 maps 조회와 madvise 비용이 추가되며, 이
일회성 APK 준비 시간은 아래 셸 벤치마크에 포함되지 않는다. 호스트 매핑 제거를
악성 게스트 격리 보장으로 사용하지 않는다. 실행 권한·Android 정책·중앙 APK
실행기·기존 Rootless에는 변경이 없다.

## 교차 성능 측정

동일 APK에 이전/개선 로더를 넣고 같은 718개 fixture와 기존 가속 Rootless를
사용했다. 차수마다 18개 세트를 실행하며 세 엔진의 여섯 실행 순서를 균등하게
배치했다. 프로파일과 trace는 껐다. 게스트 시계로 셸 생성부터 `/bin/true` 30회와
종료 대기까지 측정하며 바깥 Android 실행기 준비·관찰 시간은 제외한다.

| 차수 | 이전 로더 중앙값 | 개선 로더 중앙값 | Rootless 중앙값 | 쌍별 감소율 중앙값 |
| --- | ---: | ---: | ---: | ---: |
| 1 | 47.810 ms | 36.133 ms | 49.193 ms | 24.38% |
| 2 | 46.372 ms | 34.794 ms | 48.312 ms | 24.36% |
| 3 | 46.825 ms | 35.049 ms | 48.426 ms | 24.02% |

전체 54쌍의 `1 - 개선/이전` 중앙값은 24.279%, 개선된 쌍은 54개다.
별도 넓은 작업에서는 stat/open, 인자 96개 명령, 파일 트리, Direct Python/Git도
완료했다. Python/Git 전체 중앙값은 566.773 ms였으며 이전 단계와의 추가 개선을
입증하는 교차 비교는 아니다. Rootless의 기존 저장소 정리 실패는 명시적으로
제외했으므로 전체 앱의 Rootless 비교 완료를 주장하지 않는다.

[원시 표본](results/startup-performance-2026-09-25.json)은 최종 세 차수, 전후 진단,
채택하지 않은 실험도 보존한다. [전체 회귀 보고서](results/startup-runtime-2026-09-25.json)는
입력 해시와 신호·네트워크·PTY 결과를 포함한다. CPU/RSS, cold cache, 물리 기기의
발열·전원 조건, stock SELinux enforcing 환경은 측정하지 않았다.

## 검증과 재현

```sh
clang -O2 -Wall -Wextra -Werror -fPIE -pie -fno-stack-protector \
  -mgeneral-regs-only tools/direct-linux/bootstrap-test.c \
  tools/direct-linux/syscall.S -o /tmp/direct-linux-bootstrap-test
/tmp/direct-linux-bootstrap-test
clang -O2 -Wall -Wextra -Werror tools/direct-linux/signals-exec-test.c \
  -o /tmp/direct-linux-signals-exec-test
/tmp/direct-linux-signals-exec-test
```

두 호스트 검사가 통과했다. 네이티브 warnings-as-errors 빌드, 미해결 심볼/TLS
감사, APK 서명 검증 및 JDK 17의 저장소 표준 Gradle 검사가 통과했다. 변경 없는
runtime/image 단위 검사는 UP-TO-DATE 결과를 재사용했다(180 통과·1 선택 fixture
skip, image 4 통과). 계측 APK 빌드도 성공했다.

최종 Android 전체 회귀는 118개 top-level 실행과 신호·네트워크·PTY 각각 두
시나리오를 16.073초에 통과했다. 넓은 벤치마크 실행은 157개 실행, 145개 표본을
23.413초에 완료했다. 이 숫자는 이미 실행된 기존 시나리오를 포함한다.

기존 [APK 패키징 절차](ELF.md#reproduction)에서 `fixtures.py --applications`를
사용하고, 진단에는 `directLinuxStartupProfile=true`를 추가한다. 원시 보고서는
`startup_profiles`에 PID로 연결 가능한 `sample`, `runtime`, `host` 레코드를 담는다.
벤치마크에는 이 옵션을 끄고 `directLinuxBaselineSha256`를 지정한다. APK의 기존
probe 슬롯에 이전 로더를 넣는 비교 방법은 [앞선 측정](SHORT_COMMAND_PERFORMANCE.md)과 같다.

- 최종 로더: `6a6a68e44bf174f2fa6c4f111770ce23d6e1be8a2728f142f299ad1f8115a8a3`
- 이전 로더: `45de016fc03e7902a65f01d3fb6b6fa6e35576d37671e57a3186fe48bc090ec9`
- 진단 전용 이전 로더: `31e7fbd279442902319759372910d221c84375324f85c30a118101a760d9caba`

Android MCP로 ARM64 API 35 redroid에서 설치·일반 앱 UID 실행·결과 수집을 했다.
CapEff=0, TracerPid=0, initial Seccomp=0, SELinux Disabled 환경이다. 저장 공간 부족으로
설치가 두 번 실패했으며 재생성 가능한 작업용 APK를 정리한 뒤 성공했다.
잘못된 digest 실행은 검증에서 차단됐고, 초기 진단 출력의 버퍼 경계 문제는
부모가 reap 후 각 레코드를 flush하도록 고쳤다. 실패를 통과 결과로 세지 않았다.
Context7은 정확한 계약을 제공하지 못해 Linux man-pages와 커널 원문, 실제 fork
검사로 확인했다. UI·외부 앱 작업이 없어 Replicant/codex_apps는 사용하지 않았다.
기존 미완성 스레드 작업은 보존하고 측정 로더에서 제외했다. AVF 특권 체크리스트는
이 변경에 해당하지 않으며, stock 기기 검증과 제품 통합은 여전히 남아 있다.
