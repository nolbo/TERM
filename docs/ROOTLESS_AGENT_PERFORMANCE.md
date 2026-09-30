# Rootless 코딩 에이전트 성능 개선 검토안

2026-09-30. 사용자 요청에 따라 Rootless 작업 성능 대상에 **Codex CLI와 Claude Code**를 추가하고, Direct Linux에서 검증한 프로세스 내부 syscall 처리와 ptrace 정지 감소 방향을 반영한다. 이 문서는 전환 검토안이며 전체 Direct 호환성 목표를 완료 처리하거나 Rootless 엔진 변경을 적용했다는 기록이 아니다.

## 현재 근거와 목표

Rootless는 이미 `RootlessAccelerationProfile`의 경로·stat·open/create/close 등 가속과 glibc DSO를 사용한다. 따라서 기존 가속을 새 기능으로 다시 계산하지 않으며, 현재 배포 입력의 엔진·loader·DSO hash를 고정한 뒤 추가 비용을 측정한다. Direct의 [최근 비교](../tools/direct-linux/SPAWN_COMPATIBILITY.md)는 셸 외부 명령 30회에서 가속 PRoot보다 약 12.5% 빨랐지만 이전 Direct보다는 14.5% 느렸다. 이 한 작업을 코딩 에이전트 전체의 성능으로 일반화하지 않는다.

이번 호스트 관찰에서 Codex CLI 0.159.2는 ARM64 정적 ELF다. 이 실행 파일의 내부 syscall에는 현재 glibc 공개 함수 interposition이 적용되지 않는다. 다른 Codex 배포 형식의 링크 방식까지 단정하지 않는다. Claude Code의 공식 설치 문서는 npm 설치도 native binary를 배치하며 실행 때 Node를 호출하지 않는다고 설명한다. 따라서 두 도구를 단순한 Node workload로 취급하지 않고 **실제 설치된 ELF, libc, bundled rg와 자식 도구**를 각각 확인한다. [Claude Code 설치 문서](https://code.claude.com/docs/en/setup#install-with-npm)

목표는 같은 코딩 작업을 정확하게 완료하면서 로컬 탐색·검색·읽기·수정·검증의 지연과 CPU 비용을 줄이는 것이다. 모델 추론 시간, 네트워크 대기, 인증과 사용자 승인 대기는 런타임 가속 효과와 구분한다. 전체 2배나 특정 개선율을 선제적으로 보장하지 않는다.

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

첫 기준 입력은 파일 수·경로 깊이·언어 구성·repository hash가 고정된 작은 저장소와 큰 저장소다. 기존 사용자 작업 저장소나 인증 파일은 fixture로 복사하지 않는다. 변경은 매회 독립적인 임시 workspace에서 수행하고 원본 상태로부터 다시 준비한다. 네트워크 패키지 다운로드는 기본 오프라인 로컬 측정에 넣지 않는다.

결과에는 CLI/도구/엔진/loader/DSO 버전과 hash, 배포판·kernel·페이지 크기·SELinux/seccomp, 저장 형식·저장소 위치·cache 조건을 기록한다. ARM64 glibc/musl 및 정적/동적 ELF를 구분한다. warm 측정과 최초 실행을 나누고 cold cache라고 검증하지 못한 실행을 cold로 표시하지 않는다.

- **시간:** 시작 지연, 최초 로컬 도구 결과, 도구 구간별 및 작업 전체 중앙값/p95. 병행 도구의 시간을 단순 합산하거나 전체 시간에서 빼서 모델 시간을 추정하지 않는다. 구분 불가능한 대기 구간은 미분리로 남긴다.
- **자원:** guest와 tracer의 CPU 시간, peak RSS, context switch, 자식/FD 잔존과 취소 후 회수. 제공되지 않는 기기 지표는 미측정으로 표시한다.
- **원인:** syscall별 ptrace 진입/반환 정지, guest 메모리 읽기/쓰기, 프로세스 내부 처리 적용/실패 및 기존 경로 복귀 사유. 프로세스·thread 합계를 포함한다.
- **정확성:** 실제 guest 종료 상태, 테스트·diff·출력 내용, 작업 성공률. tracer가 exit 0이어도 guest SIGSEGV를 성공으로 세지 않는다.

같은 APK·기기·입력에서 기존/후보 순서를 교대하고 최소 10쌍의 로컬 표본을 확보한다. 계측 실행과 시간 비교 실행을 구분한다. PRoot가 이미 ptrace tracer이므로 두 번째 strace 부착을 기본 계측 방법으로 삼지 않고 기존 `PROOT_PERF`와 내부 카운터를 확장한다. JSON 이벤트 수신 시각만으로 정확한 syscall 시간을 주장하지 않는다.

## Direct의 추적 감소 방향을 Rootless에 적용

기본 구조는 **검증한 자주 쓰는 호출은 프로세스 내부에서 처리하고, 나머지는 PRoot가 계속 담당하는 혼합 방식**이다. Rootless rootfs·가상 identity·경로 binding·공통 세션과 기존 실행 정책을 유지한다. 선택한 런타임의 실패를 Native/Direct/AVF 실행으로 자동 전환하지 않는다.

1. **기존 경로의 비용과 적용률부터 확인한다.** 현재 DSO 및 전용 syscall trampoline의 처리율, 에이전트 본체·rg·Git·테스트 자식별 미적용 호출과 ptrace 정지를 센다. 이미 kernel 직행인 read/write 등의 호출을 다시 최적화 대상으로 계산하지 않는다.
2. **새 가속의 첫 범위를 조회 호출 한 종류로 제한한다.** 실제 계측에서 비중이 큰 statx/newfstatat 등의 후보를 골라, 동일 binding·cwd/dirfd·fake UID/GID·에뮬레이션 link count·errno를 유지하면서 중복 왕복을 줄인다. file metadata를 장기 캐시하거나 실제 오류를 추정한 성공으로 바꾸지 않는다.
3. **정적 ELF와 직접 syscall의 진입 처리를 별도 연구 단계로 둔다.** Direct의 libc/TLS 독립 처리·bounded pointer copy·소유권 검증을 참고한다. glibc preload만 확장해서 Codex 정적 바이너리까지 가속됐다고 주장하지 않는다. PRoot loader의 초기화, signal 처리, 공유 상태와 실제 배포 ELF에 맞는 제한된 연결을 함께 검증한다.
4. **기존 경로 복귀를 명시적으로 설계한다.** 지원하지 않는 경로·FD·신호·커널 조건은 기존 PRoot 의미로 처리한다. SIGSYS/seccomp 진입을 실험한다면 PRoot TRACE 필터 및 guest 필터와의 우선순위, handler 안의 재진입, 원래 호출로 돌아가는 protocol이 선행 조건이다. TRAP을 설치한 뒤 단순 ENOSYS 반환만으로 PRoot가 원래 호출을 다시 처리한다고 가정하지 않는다.
5. **혼합 처리의 정확성을 먼저 확인한다.** fork/clone/exec, signal/cancellation, FD 재사용·전달·unshare, cwd/rename, 두 세션에서의 파일 변경과 link2symlink 전환을 검사한다. Direct의 task registry와 exec loader를 PRoot에 그대로 이식하지 않는다. process/exec/signal은 검증된 소유자가 계속 처리하며, 0-stop 적용 범위는 증거가 있는 호출에 한정한다.

초기 제품 후보는 기존 PRoot 가속 경로에서 검증한 작은 변경이다. raw-syscall/SIGSYS 방식은 정적 바이너리 적용률과 기기 정책을 검증하는 독립 실험이며, 전체 새 Linux runtime을 다시 만드는 범위로 확대하지 않는다. Android의 상속된 seccomp·SELinux, 실행 파일 loader 정책과 에뮬레이션 hardlink 지원을 유지한다.

## 첫 개선 묶음과 채택 기준

먼저 새 대상의 실행 지원표와 로컬 기준선을 만든다. 동시에 기존 비교에서 재현된 worker exec SIGSEGV, FIFO SA_RESTART 오류, fork O_PATH pin 잔존과 에뮬레이션 raw stat identity 오류를 정확성 검사에 포함한다. preload/host 가속 on/off 대조로 원인을 분리하며 upstream 또는 가속 패치 문제로 미리 단정하지 않는다.

첫 구현 후보는 계측에서 확인한 한 병목을 수정하고 아래 조건으로 평가한다.

- 수정 대상 syscall의 실제 ptrace 정지 또는 guest 메모리 왕복이 줄어든다.
- 같은 로컬 작업의 내용·errno·종료 상태·테스트가 같고 시간·CPU의 반복 가능한 이득이 있다. 호출 수 감소만으로 제품 성능 개선을 선언하지 않는다.
- Codex와 Claude 각각의 실제 실행 gate 및 지원되는 모드에서 작업 완료를 검증한다. 미설치·미인증·kernel 제약은 해당 항목의 미검증 또는 실패로 남긴다.
- Debian의 독립 package DB에서 apt/dpkg 설치·업그레이드·삭제와 기존 session/FD/signal 검사가 회귀하지 않는다. 두 storage 형식과 가속 on/off를 포함한다.
- 실제 enforcing 및 16 KiB 기기 검증 전에는 해당 기기 지원을 주장하지 않는다. 체감 이득이 없는 후보는 기본 payload로 올리지 않는다.

## 이번 문서의 검증 범위

저장소의 현재 가속 profile·filter·protocol, 기존 성능 결과와 공식 CLI 문서를 확인했다. 호스트의 Codex 0.159.2 version/help와 정적 ARM64 ELF 형식만 확인했으며 Claude 실행 파일은 현재 PATH에서 찾지 못했다. Codex help는 host PATH alias 작성이 read-only로 실패했다는 경고와 함께 정상 출력됐다. 인증 정보는 조회하거나 복사하지 않았고 실제 모델 요청, Android CLI 설치·실행 및 새 성능 측정은 수행하지 않았다.

문서 범위여서 Android 빌드·device/UI MCP 실행은 필요하지 않았다. 새 라이브러리 API 구현이나 연결 앱 작업이 없어 Context7/codex_apps도 호출하지 않았다. 공식 CLI 문서는 OpenAI Docs와 Anthropic 원문을 사용했다. 이 문서는 실행 지원 또는 성능 향상의 통과 결과가 아니다.
