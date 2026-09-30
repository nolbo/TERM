# Rootless 실제 작업·코딩 에이전트 성능 개선

2026-09-30. Direct Linux/glibc 실험은 사용자 요청으로 폐기하고, 아래 설계의 첫 Rootless 개선 묶음을 구현했다. Android Native/Bionic과 AVF는 기존 제품 런타임으로 유지한다. 이 문서는 구현 범위와 남은 검증 gate를 함께 기록하며, 로컬 도구 재생을 실제 Codex/Claude 모델 작업 완료로 계산하지 않는다.

## 구현 범위

| 항목 | 구현과 적용 경계 |
| --- | --- |
| 실제 작업 하네스 | [workloads](../tools/rootless/workloads/README.md): apt/dpkg 1,000·10,000 파일 설치/업그레이드/삭제, 선택한 실제 패키지의 unpack/remove, Git/rg 10k·100k·고정 저장소 탐색, Python 수정/테스트/incremental build, 반복 도구 재생과 두 workspace의 취소·회수 |
| 파일 시간 가속 | [proot-fd-utimens.patch](../tools/rootless/proot-fd-utimens.patch): ARM64의 `utimensat(fd, NULL, times, flags)`만 PRoot 추적을 생략한다. 동적 libc와 정적/raw 호출을 모두 처리하되 경로 호출·알 수 없는 확장·USERLAND는 기존 경로를 유지한다. |
| exec 상태 | [proot-exec-thread.patch](../tools/rootless/proot-exec-thread.patch): exec event의 실제 이전 TID로 실행 태스크를 선택하고 leader PID 변경 후 register·loader·parent와 binding 수명을 이어간다. |
| syscall 재시작 | [proot-restart-path.patch](../tools/rootless/proot-restart-path.patch): ARM64의 entry-only 경로 호출이 신호로 재시작될 때 원래 guest 인자를 signal frame에 보존한다. PC와 SA_RESTART/EINTR 결정은 커널에 맡기며 일반 open의 exit stop은 추가하지 않는다. |
| 임시 FD 소유권 | [ABI v9](../tools/rootless/faststat/README.md)와 [pin handshake](../tools/rootless/proot-pin-fd.patch): exec 후 첫 clone/fork/vfork 전까지만 임시 FD를 만들고 전체 작업에서 신호를 막는다. 첫 clone 뒤에는 해당 경로 조회가 tracer로 돌아가며 다음 exec에서 다시 허용한다. 사용자 FD를 추정해 닫지 않는다. |
| hardlink 정확성 | [identity](../tools/rootless/proot-stat-identity.patch)·[mutation](../tools/rootless/proot-link-path.patch): callback 순서와 fork 세대에 무관한 가상 UID/GID, 실패한 unlink/rename의 내용·nlink 보존, same-inode/NOREPLACE/EXCHANGE 의미와 업그레이드 뒤 backing 회수 |
| 앱 연결 | [로컬 빌드 입력](../tools/rootless/WORKLOAD_PAYLOAD.md): accelerator·stdio·workload 옵션을 함께 선택하며 엔진/loader/DSO 고정 SHA와 ELF 정렬을 검증한다. 세션과 서비스 소유권, 배포판의 독립 package DB와 기존 저장 형식은 유지한다. |
| CLI gate | 실제 설치 ELF의 version/help 검사와 명시적인 인증 실행/독립 결과 검증은 별도 단계다. 인증은 검색·복사하지 않으며 sandbox를 자동 해제하지 않는다. npm/pip의 offline 설치, 인증된 PTY 시작/재개는 이번 하네스의 완료 항목이 아니다. |

Direct의 dispatcher와 별도 새 런타임을 이식하지 않았다. 프로세스 내부 가속과 PRoot의 호환성 처리를 함께 쓰는 기존 구조를 유지하고, 이미 적용된 가속 위에 정확성 수정과 한 종류의 추가 syscall 경로를 얹었다. 정적 ELF의 일반 경로 syscall을 모두 프로세스 내부로 옮기는 것은 독립 연구 단계로 남는다.


## 현재 근거와 목표

Rootless는 이미 `RootlessAccelerationProfile`의 경로·stat·open/create/close 등 가속과 glibc DSO를 사용한다. 따라서 기존 가속을 새 기능으로 다시 계산하지 않으며, 현재 배포 입력의 엔진·loader·DSO hash를 고정한 뒤 추가 비용을 측정한다. Direct의 [최근 비교](../tools/direct-linux/SPAWN_COMPATIBILITY.md)는 셸 외부 명령 30회에서 가속 PRoot보다 약 12.5% 빨랐지만 이전 Direct보다는 14.5% 느렸다. 이 한 작업을 코딩 에이전트 전체의 성능으로 일반화하지 않는다.

이번 호스트 관찰에서 Codex CLI 0.159.2는 ARM64 정적 ELF다. 이 실행 파일의 내부 syscall에는 현재 glibc 공개 함수 interposition이 적용되지 않는다. 다른 Codex 배포 형식의 링크 방식까지 단정하지 않는다. Claude Code의 공식 설치 문서는 npm 설치도 native binary를 배치하며 실행 때 Node를 호출하지 않는다고 설명한다. 따라서 두 도구를 단순한 Node workload로 취급하지 않고 **실제 설치된 ELF, libc, bundled rg와 자식 도구**를 각각 확인한다. [Claude Code 설치 문서](https://code.claude.com/docs/en/setup#install-with-npm)

목표는 같은 코딩 작업을 정확하게 완료하면서 로컬 탐색·검색·읽기·수정·검증의 지연과 CPU 비용을 줄이는 것이다. 모델 추론 시간, 네트워크 대기, 인증과 사용자 승인 대기는 런타임 가속 효과와 구분한다. 전체 2배나 특정 개선율을 선제적으로 보장하지 않는다.

## 체감 개선 여지를 기준으로 한 우선순위

작은 파일의 조회·생성·교체·삭제와 짧은 프로세스 실행이 누적되는 실제 작업을 먼저 다룬다. 아래 순위는 측정·설계 순서이며 성능 저하나 개선율이 이미 검증됐다는 뜻은 아니다. 현재 가속된 PRoot에서 기준선을 만든 뒤 **실제 대기 시간, 반복 빈도, tracer 비용의 비중**으로 구현 순서를 조정한다. 코딩 에이전트 작업은 아래의 설치·탐색·검증을 포함하는 통합 평가 대상이다.

| 우선순위 | 체감 후보와 고정 작업 | 개선할 비용의 후보 | 정확성·체감 확인 |
| --- | --- | --- | --- |
| 1 | **패키지 설치·업데이트:** 로컬 패키지의 apt/dpkg 설치·업그레이드·삭제, 이후 npm/pip 설치 | 파일별 경로 변환, 임시 경로 정리, 권한·소유자·시간 설정, 추적 정지와 guest 메모리 왕복 | 다운로드 이후 설치 구간의 시간, 설치 내용·링크·모드·시간·package DB와 실패 결과 |
| 1 | **큰 저장소 탐색:** Git status/diff, rg 파일 목록·내용 검색, 필요한 파일 읽기 | 디렉터리 순회, statx/newfstatat, open/close, 깊은 경로와 기존 가속 미적용 호출 | 최초 유효 결과와 전체 완료 시간, 파일 목록·검색 결과·diff 내용, ignore 및 symlink 의미 |
| 2 | **작은 수정 뒤 검증:** Python import와 선택 테스트, 프로젝트 설정·의존성 검사·incremental build | 반복 파일 조회, 새 프로세스 시작, 짧은 자식 프로그램 실행 | 수정부터 테스트 완료까지의 시간, 테스트 결과·산출물·guest 종료 상태 |
| 2 | **에이전트의 반복 도구 실행:** 조사 → 검색 → 읽기 → 수정 → Git 확인 → 테스트 | 위 비용이 여러 도구 호출에 누적되는 구간과 정적 ELF/직접 syscall의 가속 미적용 | 최초 로컬 결과, 도구를 기다리는 구간과 작업 완료 시간, 최종 수정·테스트 성공률 |
| 3 | **두 작업의 동시 진행:** 독립 workspace의 탐색·테스트 또는 두 에이전트 세션 | tracer CPU·추적 이벤트 처리 지연, 자식·FD 관리 비용 | 단독 실행 대비 응답 지연과 p95, 총 완료 시간, 취소 후 회수·격리·hang 여부 |

패키지 시험은 먼저 1,000개와 10,000개의 작은 파일을 가진 고정 fixture 및 hash가 고정된 실제 패키지 집합을 사용한다. apt/dpkg는 독립 임시 root와 package DB에서 수행한다. npm은 고정 lockfile과 준비된 offline cache, pip는 고정 wheelhouse와 매회 새 가상환경을 사용한다. 준비·다운로드·fixture 생성·검증·정리 시간을 설치 시간과 분리하고, 패키지 작업의 전체 시간도 함께 기록한다. 압축 해제의 계산 비용과 파일 배치 비용을 계측으로 구분한다. 누락된 offline 입력을 받느라 기다린 실행은 로컬 설치 비교에 포함하지 않는다.

탐색 시험은 같은 파일 구성·경로 깊이 비율을 가진 10,000개와 100,000개 파일의 저장소, 그리고 hash가 고정된 실제 저장소를 준비한다. Git tracked/untracked 구성과 ignore 규칙을 고정하고, 일반 소스 저장소와 의존성 디렉터리가 큰 저장소를 구분한다. 기본 ignore 동작을 유지한 검색과 의도적으로 넓힌 검색을 별도 작업으로 기록한다. checkout 등 파일 교체는 독립 workspace에서만 수행한다. 테스트·build는 고정된 작은 수정 이후의 실제 incremental 작업을 사용하고, 매회 의존성 및 산출물 상태를 같게 준비한다.

기존 [dpkg 진단](ROOTLESS_EMULATED_ACCELERATION.md#2026-09-20-dpkg-압축-해제배치의-임시-경로-정리)은 100개 파일에서도 unlinkat 320회와 파일별 fchmod/fchown/utimensat 호출을 확인했다. 이는 설치 경로의 후보 비용을 뒷받침하지만 현재 엔진과 Native의 전체 설치 속도 차이를 증명하지 않는다. [최근 셸 비교](../tools/direct-linux/SPAWN_COMPATIBILITY.md)의 약 12.5% 차이도 절대 시간으로 약 6ms이므로 체감 개선의 대표 지표로 삼지 않는다.

큰 파일의 순차 read/write, 순수 계산·압축, 모델 응답 및 네트워크 대기는 첫 최적화 대상의 우선순위를 낮춘다. 기존 [순차 I/O 검사](ROOTLESS_IO_GOAL_AUDIT.md)는 해당 조건에서 추적 감소만으로 얻을 여유가 작았다. 실제 다른 입력에서 tracer 병목이 확인되면 다시 평가한다. 모델·다운로드 대기는 전체 사용자 시간에 표시하되 Rootless 엔진의 개선으로 계산하지 않는다.

## 사용자 대기를 줄이는 채택 판단

후보별로 기준/후보의 중앙값·p95, 절대 절감 ms/초와 개선율, 실제 작업에서의 반복 횟수, 해당 구간이 사용자 대기에서 차지하는 비중을 함께 기록한다. 겹쳐 실행되는 구간은 단순 합산하지 않고 전체 작업 경과 시간으로 평가한다. 예를 들어 6ms를 줄이는 12% 개선보다 자주 하는 설치·검증에서 0.5초를 줄이는 5% 개선을 우선 검토한다. 이는 우선순위의 예시이며 고정된 체감 임계값이나 개선 약속이 아니다.

단일 syscall 또는 합성 반복 시험은 원인 확인에 사용하고, 기본 적용 판단에는 위 실제 작업의 시간을 사용한다. 작은 작업과 큰 작업을 함께 비교하여 호출 수가 많을 때만 이기는 후보, 최초 실행만 느려지는 후보를 드러낸다. 중앙값 이득과 p95·CPU·메모리 악화를 함께 보고하며, 반복 표본의 변동을 넘는 이득이 확인되지 않으면 추가 계측 대상으로 남긴다. 적은 표본의 p95는 탐색 결과로 표시하고 꼬리 지연에 대한 결론에는 추가 표본을 확보한다. 실제 에이전트 실행 전의 로컬 도구 재생 결과는 에이전트 전체 체감 개선으로 승격하지 않는다.

## 대상과 검증 작업

| 대상 | 고정할 실제 작업 | 확인할 결과 |
| --- | --- | --- |
| Codex CLI | 저장소 조사 → 관련 코드 검색 → 작은 결함 수정 → 테스트 → diff 확인 | 요구한 수정·테스트 결과·변경 범위와 실제 도구 종료 상태 |
| Claude Code | 같은 입력·완료 조건의 조사/수정/테스트와 세션 재개 | 최종 산출물, 도구 오류, 권한 거부, 재개 후 작업 상태 |
| 공통 로컬 도구 | rg 파일 목록/내용 검색, Git status/diff, 다수 파일 읽기, atomic 교체, 테스트·incremental build | 출력/내용 hash, 파일 수, 오류와 종료 상태 |
| 병행 작업 | 독립 workspace의 두 세션, 여러 검색·테스트 자식, 취소 후 재실행 | FD/자식 프로세스 회수, workspace 격리, hang·race 유무 |

인증 없이 가능한 version/help·기본 실행 검사를 첫 단계로 둔다. 실제 에이전트 작업은 인증 및 고정된 입력 저장소가 준비된 별도 단계에서 검사한다. CLI 시작 성공, 로컬 도구 재생, 실제 모델을 사용하는 작업 완료를 서로 다른 결과로 보고한다. 유사한 shell script만 통과한 것을 Codex/Claude Code 지원으로 세지 않는다.

실제 실행은 Codex의 `codex exec --json`과 Claude Code의 `claude -p --output-format stream-json --verbose` 같은 공식 비대화형 출력을 활용한다. 각 CLI 버전의 help에서 옵션을 확인하고, 모델·추론 설정·권한 설정·plugin/MCP·workspace 및 세션 상태를 고정한다. PTY 기반 대화형 시작·취소·세션 재개는 별도 검사한다. [OpenAI Docs](https://developers.openai.com/codex/noninteractive/), [Claude Code 비대화형 실행](https://code.claude.com/docs/en/headless)

코딩 에이전트 자체 sandbox의 동작도 실행 조건에 포함한다. PRoot를 sandbox 대체물로 간주하지 않는다. 필요한 kernel 기능이나 권한이 부족하면 지원 제약으로 기록하고, 보안 기능을 자동으로 해제해 성공 수치를 만들지 않는다.

## 측정 계약

두 종류의 측정을 함께 유지한다.

1. **고정 로컬 작업 재생:** 결정된 명령·입력·출력 검증을 반복하여 파일/프로세스 처리 비용을 비교한다. 모델 서비스 변동이 없어 최적화 원인을 분리하기 좋지만 실제 에이전트 완료 검증을 대체하지 않는다.
2. **실제 에이전트 작업:** 같은 도구·모델 설정과 새 fixture에서 동일 완료 조건을 반복한다. 도구 실행 횟수나 선택한 작업이 달라질 수 있으므로 최종 성공률, 작업량과 전체 시간을 함께 기록한다. Codex와 Claude의 모델 성능을 서로 순위 매기지 않고 각각 기존/후보 Rootless를 비교한다.

첫 기준 입력은 위 우선순위에 따른 설치 fixture와 파일 수·경로 깊이·언어 구성·repository hash가 고정된 작은 저장소와 큰 저장소다. 기존 사용자 작업 저장소나 인증 파일은 fixture로 복사하지 않는다. 변경은 매회 독립적인 임시 workspace에서 수행하고 원본 상태로부터 다시 준비한다. 네트워크 패키지 다운로드는 기본 오프라인 로컬 측정에 넣지 않는다.

결과에는 CLI/도구/엔진/loader/DSO 버전과 hash, 배포판·kernel·페이지 크기·SELinux/seccomp, 저장 형식·저장소 위치·cache 조건을 기록한다. ARM64 glibc/musl 및 정적/동적 ELF를 구분한다. warm 측정과 최초 실행을 나누고 cold cache라고 검증하지 못한 실행을 cold로 표시하지 않는다.

- **시간:** 시작 지연, 최초 로컬 도구 결과, 도구 구간별 및 작업 전체 중앙값/p95. 병행 도구의 시간을 단순 합산하거나 전체 시간에서 빼서 모델 시간을 추정하지 않는다. 구분 불가능한 대기 구간은 미분리로 남긴다.
- **자원:** guest와 tracer의 CPU 시간, peak RSS, context switch, 자식/FD 잔존과 취소 후 회수. 제공되지 않는 기기 지표는 미측정으로 표시한다.
- **원인:** syscall별 ptrace 진입/반환 정지, guest 메모리 읽기/쓰기, 프로세스 내부 처리 적용/실패 및 기존 경로 복귀 사유. 프로세스·thread 합계를 포함한다.
- **정확성:** 실제 guest 종료 상태, 테스트·diff·출력 내용, 작업 성공률. tracer가 exit 0이어도 guest SIGSEGV를 성공으로 세지 않는다.

같은 APK·기기·입력에서 기존/후보 순서를 교대하고 최소 10쌍의 로컬 표본을 확보한다. 계측 실행과 시간 비교 실행을 구분한다. PRoot가 이미 ptrace tracer이므로 두 번째 strace 부착을 기본 계측 방법으로 삼지 않고 기존 `PROOT_PERF`와 내부 카운터를 확장한다. JSON 이벤트 수신 시각만으로 정확한 syscall 시간을 주장하지 않는다.

## Direct의 추적 감소 방향을 Rootless에 적용

기본 구조는 **검증한 자주 쓰는 호출은 프로세스 내부에서 처리하고, 나머지는 PRoot가 계속 담당하는 혼합 방식**이다. Rootless rootfs·가상 identity·경로 binding·공통 세션과 기존 실행 정책을 유지한다. 선택한 런타임의 실패를 Native/Direct/AVF 실행으로 자동 전환하지 않는다.

1. **기존 경로의 비용과 적용률부터 확인한다.** 현재 DSO 및 전용 syscall trampoline의 처리율, 에이전트 본체·rg·Git·테스트 자식별 미적용 호출과 ptrace 정지를 센다. 이미 kernel 직행인 read/write 등의 호출을 다시 최적화 대상으로 계산하지 않는다.
2. **새 가속의 첫 범위를 파일 호출 한 종류로 제한한다.** 설치·탐색 기준선에서 비중이 큰 statx/newfstatat 또는 fchmod/fchown/utimensat·경로 처리 후보를 골라, 동일 binding·cwd/dirfd·fake UID/GID·에뮬레이션 link count·errno를 유지하면서 중복 왕복을 줄인다. 변경 호출은 실제 커널 권한·오류·취소와 가상 메타데이터 의미를 보존한다. file metadata를 장기 캐시하거나 실제 오류를 추정한 성공으로 바꾸지 않는다.
3. **정적 ELF와 직접 syscall의 진입 처리를 별도 연구 단계로 둔다.** Direct의 libc/TLS 독립 처리·bounded pointer copy·소유권 검증을 참고한다. glibc preload만 확장해서 Codex 정적 바이너리까지 가속됐다고 주장하지 않는다. PRoot loader의 초기화, signal 처리, 공유 상태와 실제 배포 ELF에 맞는 제한된 연결을 함께 검증한다.
4. **기존 경로 복귀를 명시적으로 설계한다.** 지원하지 않는 경로·FD·신호·커널 조건은 기존 PRoot 의미로 처리한다. SIGSYS/seccomp 진입을 실험한다면 PRoot TRACE 필터 및 guest 필터와의 우선순위, handler 안의 재진입, 원래 호출로 돌아가는 protocol이 선행 조건이다. TRAP을 설치한 뒤 단순 ENOSYS 반환만으로 PRoot가 원래 호출을 다시 처리한다고 가정하지 않는다.
5. **혼합 처리의 정확성을 먼저 확인한다.** fork/clone/exec, signal/cancellation, FD 재사용·전달·unshare, cwd/rename, 두 세션에서의 파일 변경과 link2symlink 전환을 검사한다. Direct의 task registry와 exec loader를 PRoot에 그대로 이식하지 않는다. process/exec/signal은 검증된 소유자가 계속 처리하며, 0-stop 적용 범위는 증거가 있는 호출에 한정한다.

초기 제품 후보는 기존 PRoot 가속 경로에서 검증한 작은 변경이다. raw-syscall/SIGSYS 방식은 정적 바이너리 적용률과 기기 정책을 검증하는 독립 실험이며, 전체 새 Linux runtime을 다시 만드는 범위로 확대하지 않는다. Android의 상속된 seccomp·SELinux, 실행 파일 loader 정책과 에뮬레이션 hardlink 지원을 유지한다.

## 첫 개선 묶음과 채택 기준

먼저 새 대상의 실행 지원표와 설치·큰 저장소 탐색의 로컬 기준선을 만든다. 동시에 기존 비교에서 재현된 worker exec SIGSEGV, FIFO SA_RESTART 오류, fork O_PATH pin 잔존과 에뮬레이션 raw stat identity 오류를 정확성 검사에 포함한다. preload/host 가속 on/off 대조로 원인을 분리하며 upstream 또는 가속 패치 문제로 미리 단정하지 않는다. 실패하는 실행 경로는 정확성을 먼저 수정한 뒤 성능 후보로 평가한다.

첫 구현 후보는 계측에서 확인한 한 병목을 수정하고 아래 조건으로 평가한다.

- 수정 대상 syscall의 실제 ptrace 정지 또는 guest 메모리 왕복이 줄어든다.
- 같은 로컬 작업의 내용·errno·종료 상태·테스트가 같고 시간·CPU의 반복 가능한 이득이 있다. 호출 수 감소만으로 제품 성능 개선을 선언하지 않는다.
- 설치 또는 탐색 등 우선 작업에서 절대 대기 시간 감소가 확인되고, 작은 작업·최초 실행·동시 진행의 악화도 공개된다. 대표 작업을 늘리거나 성공한 단일 반복 시험으로 교체해 실제 작업의 회귀를 숨기지 않는다.
- Codex와 Claude 각각의 실제 실행 gate 및 지원되는 모드에서 작업 완료를 검증한다. 미설치·미인증·kernel 제약은 해당 항목의 미검증 또는 실패로 남긴다.
- Debian의 독립 package DB에서 apt/dpkg 설치·업그레이드·삭제와 기존 session/FD/signal 검사가 회귀하지 않는다. 두 storage 형식과 가속 on/off를 포함한다.
- 실제 enforcing 및 16 KiB 기기 검증 전에는 해당 기기 지원을 주장하지 않는다. 체감 이득이 없는 후보는 기본 payload로 올리지 않는다.

## 검증 기록

최초 설계는 현재 가속 profile/filter/protocol, 기존 결과와 공식 CLI 문서를 근거로 작성했다. 이후 실제 fixture/하네스와 엔진 패치를 구현했으며, 소스만으로 성공을 판단하지 않는다. 현행 채널 입력인 `rootless-runtime-20260920-emulated.3`의 엔진·합본 DSO를 기준선으로 고정하고, 후보는 [로컬 빌드 문서](../tools/rootless/WORKLOAD_PAYLOAD.md)의 별도 SHA로 검증한다. 기존 배포 입력의 hash를 덮어쓰거나 새 런타임을 배포한 것은 아니다.

호스트에서는 실제 출력·오류 검증과 static/dynamic exec/fork·identity·hardlink 회귀를 수행했다. Android에서는 별도 시험 APK의 동일 엔진 바이트를 기존 앱의 일반 UID로 실행하며, 사용자 세션과 package DB를 변경하지 않는 scratch 입력을 썼다. 최종 시간 비교는 대표 dpkg 1,000파일 작업의 10쌍으로 한정했다. 12종 전체를 10쌍 실행하던 장기 배치는 검증 시간이 과도하다는 사용자 피드백 후 중단했다. 완료한 반복과 미완료 범위를 구분하며, 나머지 작업의 소수 표본은 정확성·회귀 관찰이다.

실제 dpkg 업그레이드/삭제 시험이 최초 link 변경 후보의 orphan backing을 검출했다. 일반 파일→emulated hardlink 교체의 빠른 경로가 exit accounting을 생략한 원인을 수정하고, 해당 조합과 디렉터리 완전 제거를 회귀 fixture에 추가했다. 실패를 검증 완화나 0 종료 처리로 숨기지 않았다.

### 2026-09-30 결과와 채택 판단

| 검증 | 결과와 해석 |
| --- | --- |
| 호스트 exec/fork/signal | [100/100 PASS](../tools/rootless/regression/results/host-restart-2026-09-30.json): native/emulated, plain/tracer/preload, static/dynamic 조합. 수정 전 엔진은 새 signal assertion에서 실패했다. |
| Android 정확성 | [56/56 PASS](../tools/rootless/regression/results/android-correctness-2026-09-30.json): API 35 ARM64·Ubuntu 24.04, static/dynamic·두 storage 모드·identity·link mutation·exec/fork·signal/FD. 실제 guest assertion과 종료 상태를 함께 확인했다. |
| Debian 실제 작업 | [12종·24회 PASS](../tools/rootless/workloads/results/android-debian-workloads-2026-09-30.json): Debian 13의 apt/dpkg 1k·10k, offline 패키지, Git/rg 10k·100k·고정 subtree, Python·로컬 재생·두 workspace·Codex version/help. Claude 미설치는 별도 CLI SKIP이며 실제 모델 작업은 수행하지 않았다. |
| Debian native package | [preload on/off PASS](../tools/rootless/workloads/results/android-native-package-2026-09-30.json): native hardlink에서 dpkg 1,000파일 설치·업그레이드·삭제. tracer 가속은 두 설정에 유지했으며 전체 가속 off로 표시하지 않는다. |
| 대표 dpkg 시간 | [10쌍·20회 PASS](../tools/rootless/workloads/results/android-dpkg-1000-2026-09-30.json): 명령 시간 중앙값 **3.305→3.327초**, 후보 약 0.7% 느림. worker workflow는 **3.621→3.839초**. 뚜렷한 시간 이득이 없으며 p95는 각각 13.489·14.121초로 큰 변동을 포함한다. |
| 자원 | 같은 dpkg 기록의 install-unpack `wait4` system CPU 중앙값 **398.38→309.55ms**, peak RSS **22,228→22,228KiB**. 개별 명령의 계수이며 전체 workflow CPU나 guest/tracer 분리 값이 아니다. 누락 값과 병행 구간은 추정 합산하지 않는다. |
| 큰 저장소 회귀 | 개정 하네스의 100k 단일 쌍은 명령 합계 **33.97→54.79초**, worker workflow **178.47→204.14초**. [초기 하네스의 완료한 두 쌍](../tools/rootless/workloads/results/android-initial-preparation-2026-09-30.json)과 [3회 Git 진단](../tools/rootless/workloads/results/git-preload-diagnostic-2026-09-30.json)에서도 같은 회귀 방향을 관찰했다. 서로 다른 하네스 표본을 합치지 않는다. |
| FD 시간 호출 원인 | [별도 diagnostic](../tools/rootless/workloads/results/android-fd-utimens-diagnostic-2026-09-30.json): 같은 후보의 raw FD `utimensat` 10,000회 enter stop **10,000→0**, 명령 전체 enter stop **10,070→70**. micro 구간 **148.51→9.09ms**는 계측한 단일 반복이며 실제 설치·에이전트 개선율로 환산하지 않는다. |
| 빌드·검사 | JDK 17 canonical Gradle PASS: runtime 189개 중 188 PASS·입력 미제공 archive 검사 1 SKIP, image 4 PASS. 하네스 Python 26개 중 25 PASS·host package control 1 SKIP. 고정 캐시에서 엔진·loader·합본 DSO 재빌드 SHA 재현과 APK 바이트 검증 PASS. |

**새 후보는 기본 배포 입력으로 채택하지 않는다.** FD 호출의 추적 감소와 정확성 수정은 검증했지만 대표 패키지의 전체 시간 이득이 없고 큰 저장소·Python·동시 작업에서 회귀가 있다. ABI v9가 첫 clone 뒤 임시 FD가 필요한 경로 가속을 중단하는 비용이 Git preload 진단에서 확인됐다. `core.preloadIndex=false`는 후보의 회귀를 줄였지만 기존 기본 설정보다 느렸으며 사용자 Git 설정과 제품 기본 설정을 바꾸지 않았다. 로컬 옵션과 고정 해시로 다음 개선을 재현·검토할 수 있게 한다.

하네스는 도구 버전 조회를 측정 뒤로 옮겨 fixture 준비 전에 불필요한 fork가 가속 상태를 바꾸지 않도록 했다. 이것만으로 큰 저장소 준비 비용이 사라지지는 않았다. 최초 240회 계획을 완료한 것으로 표시하지 않으며 완료한 subset과 별도 10쌍 dpkg 기록을 구분한다. worker workflow 시간은 준비·검증을 포함하고 런타임 launch·버전 조회·바깥 Android scratch 정리는 제외한다. 시험 환경은 SELinux Disabled·4 KiB 페이지인 API 35 기기이며 enforcing·16 KiB 물리 기기 지원은 입증하지 않는다.

다음 제품 적용 과제는 임시 FD 소유권을 지키면서 다중 스레드 경로 가속의 회귀를 줄이는 것이다. npm/pip offline 설치, 실제 Codex/Claude 인증 작업, PTY 시작·취소·세션 재개 및 enforcing·16 KiB 기기 검사는 이번 첫 묶음의 완료 항목이 아니다. PRoot를 에이전트 sandbox의 대체물로 취급하지 않는다.

### 가속 해제 비용과 독립 작업의 후속 개선

[후속 구현·검증](../tools/rootless/faststat/FALLBACK_COST.md)은 해제된 pin 자격을 thread별로 기억하여 반복 signal mask·PID 조회를 생략하고, clone의 해제 범위를 같은 보수적 FD 계통에 한정한다. fork 자손과 다른 PID의 `CLONE_FILES` 공유는 같은 계통에 남고 자신의 exec 전에는 가속되지 않는다. 독립적으로 exec한 다른 작업은 가속을 유지한다. 같은 Git 작업이 생성한 thread의 경로 가속은 아직 복원하지 않았다.

최종 `7d06…` 엔진·`80cb…` DSO는 호스트 102/102·Android 62/62 assertion과 canonical Gradle·APK SHA 검증을 통과했다. 독립 reader/peer-fork 진단 3쌍에서 stat/lstat 10,000회 구간 중앙값은 262.29→93.53ms이며 이는 실제 Git·설치·에이전트 전체 개선율이 아니다. 첫 dispatcher 수정만 적용한 `85dc…` 엔진·`80cb…` DSO의 Git 10k 두 쌍은 system CPU 감소에도 wall time 이득이 뚜렷하지 않았다. 위 전체 작업 표는 최초 `85dc…`/`619b…` 후보의 측정이며 최종 후보의 실제 작업 시간으로 합치지 않는다. 실제 작업의 10쌍·회귀 없는 이득이 아직 충족되지 않아 기본 배포 채택은 계속 보류한다.
