# 프로그램 스크롤과 Android 화면 수명

## 분석 근거

2026-09-30 분석 시 호스트에 설치된 Codex CLI는 `0.159.2`다. 현재 [공식 설정 문서](https://learn.chatgpt.com/docs/config-file/config-reference)는 `tui.alternate_screen`과 `tui.raw_output_mode`를 구분한다. Context7에서 확인한 [Codex 마우스 설정 코드](https://github.com/openai/codex/blob/main/codex-rs/tui/src/tui/alternate_screen.rs)는 포인터 캡처에 `1000`, `1002`, `1003`, `1006`을 사용하고, 캡처 시 `1007`을 끄며 캡처 해제 시 다시 켠다. CLI 버전만으로 현재 세션의 입력 모드를 결정하지 않고 프로그램이 보낸 제어 시퀀스를 따른다.

설치된 `0.159.2` 바이너리에서도 `1000`, `1002`, `1003`, `1004`, `1006`, `1007`, `1049`, `2026`의 설정·해제 시퀀스를 확인했다. 이는 바이너리에 해당 프로토콜 경로가 있다는 근거이며 실제 계정 대화의 실행 결과를 뜻하지 않는다.

## 터미널이 제공하는 요소

| 요소 | 동작 |
| --- | --- |
| 일반 화면 스크롤백 | 터미널 기록을 이동한다. 프로그램 출력이 추가되면 읽고 있던 줄에 화면을 고정한다. |
| `1000` / `1002` / `1003` | 클릭 / 버튼을 누른 이동 / 모든 포인터 이동 중 하나를 선택한다. 세 모드는 서로 배타적이다. 손가락 스크롤과 마우스 휠을 프로그램의 휠 입력으로 전달한다. |
| `1006` | SGR 마우스 좌표와 휠 버튼 `64` / `65`를 ASCII 숫자로 전달한다. Android 언어 설정이 숫자 표현을 바꾸지 않는다. |
| `1007` | 마우스 캡처가 없는 대체 화면에서 휠을 위·아래 커서 키로 변환한다. 기본값은 켜짐이며 프로그램이 끌 수 있다. |
| `1049` 및 대체 화면 | 프로그램이 그린 현재 화면을 표시한다. 프로그램 내부의 기록과 스크롤 위치는 프로그램이 관리한다. |
| Page Up / Page Down | 프로그램 키 입력으로 전달한다. 기존 Shift+Page Up / Down 스크롤 동작도 유지한다. |
| `1004` | 프로그램이 요청한 경우 창의 포커스 상실·복귀를 `CSI O` / `CSI I`로 전달한다. |
| `2004`, kitty 키보드 상태 | 프로그램이 요청한 붙여넣기·키보드 프로토콜을 기존 에뮬레이터 상태와 함께 유지한다. |
| 스크롤 영역·커서·문자 스타일·UTF-8 파서 | 백그라운드 출력도 동일한 에뮬레이터에 순서대로 적용한다. 화면 경계에서 끊긴 제어 시퀀스를 이어서 처리한다. |
| `2026` 동기 출력 | 파서는 계속 출력과 입력을 처리하며 완성된 프레임을 표시한다. 같은 셀 크기로 화면을 다시 연결해도 출력 배치 장벽을 해제하지 않는다. 최대 대기 시간은 기존의 1초다. |

마우스 휠은 이동량과 소수 단위 잔여량을 누적한다. 수직 이동이 없는 가로 휠 이벤트로 아래 방향 입력을 만들지 않는다. 마우스 캡처가 활성화된 일반 화면에서도 표시 위치는 프로그램의 현재 화면으로 돌아간다.

Neovim의 `mouse` 설정처럼 마우스를 켠 프로그램은 휠 이벤트를 받는다. 마우스를 끈 대체 화면 프로그램은 `1007`이 켜진 경우 커서 키를 받는다. Codex의 raw 출력 등 일반 화면 모드는 터미널 기록을 사용한다. 터미널은 프로그램 이름에 따른 예외를 두지 않는다.

## Fullscreen VT/xterm 호환성

2026-10-01 변경은 [Xterm Control Sequences](https://invisible-island.net/xterm/ctlseqs/ctlseqs.html), [Xterm 커서 구현](https://github.com/ThomasDickey/xterm-snapshots/blob/master/cursor.c), [soft/full reset 설명](https://invisible-island.net/xterm/manpage/xterm.html)을 기준으로 한다. Context7의 xterm 자료는 과거 문서의 gist 사본을 반환했으므로 최종 동작은 공식 문서와 원본 구현으로 대조했다. VT510 HTML 문서 조회는 실패해 xterm 공식 자료로 확인했다.

| 제어 시퀀스 | 동작 |
| --- | --- |
| `?47h` / `?47l` | 기본·대체 화면만 전환한다. 대체 화면의 문자·스타일·줄바꿈·이미지를 보존하며 커서를 암묵적으로 저장·복원하지 않는다. |
| `?1047h` / `?1047l` | 진입 시 기존 대체 화면을 유지하고, 대체 화면에서 나갈 때 그 화면을 지운다. 커서를 암묵적으로 저장·복원하지 않는다. |
| `?1049h` / `?1049l` | 진입 시 커서를 저장하고 대체 화면을 지운다. 종료 시 기본 화면과 저장 커서·문자 속성을 복원한다. 기본 화면의 스크롤백은 유지된다. |
| `ESC 7` / `ESC 8`, `?1048`, `CSI s` / `CSI u` | 화면마다 독립적인 저장 커서를 사용한다. 문자·밑줄 색상·문자 집합·원점과 마지막 셀에서 다음 문자를 기다리는 줄바꿈 상태를 저장한다. 현재 `DECAWM` 설정 자체는 커서 복원으로 변경하지 않는다. `CSI s`는 좌우 여백 모드가 꺼져 있을 때 커서 저장이다. |
| `CSI ? Pm s` / `CSI ? Pm r` | 지원하는 private mode의 켜짐·꺼짐을 각각 저장·복원한다. 원점 모드 복원으로 커서를 홈으로 이동하지 않는다. `47`·`1047`·`1049`의 화면 선택 캐시는 공유하며 화면을 지우거나 커서를 복원하지 않는다. |
| `DECOM`, `CUP`·`CHA`·`HPA`·`VPA`, `CPR` | 원점 모드에서 좌표와 커서 질의는 상하·좌우 여백에 상대적이다. 한 축을 바꿀 때 다른 축에 여백을 다시 더하지 않는다. 원점 모드 설정·해제는 홈으로 이동한다. Xterm `?6n` 응답은 기본 페이지 번호를 생략한 `CSI ? row ; col R`이다. |
| `CUU`·`CUD`·`CUF`·`CUB`, `CNL`·`CPL`·`VPR` | 접근하는 여백에서 멈추고, 이미 그 여백 밖이면 화면 경계를 사용한다. 상대 이동에 원점 오프셋을 다시 더하지 않는다. |
| `DECSTBM`·`DECSLRM`, `IL`·`DL`, `ICH`·`DCH`, `IND`·`RI` | 잘못된 여백은 기존 영역과 커서를 바꾸지 않는다. 줄 편집과 인덱스 스크롤은 유효한 영역에서만 수행하며 영역 밖 상태 행·열을 보존한다. 문자 삽입·삭제는 오른쪽 여백에서 끝난다. 영역 아래의 줄바꿈 출력으로 해당 영역을 잘못 스크롤하지 않는다. |
| `?45` | 자동 줄바꿈이 켜져 있을 때 BS·CUB가 이어진 논리 행 안에서 역방향 줄바꿈한다. 기본값은 꺼짐이다. 강제 개행·영역의 상단을 넘지 않으며 기존 줄바꿈 메타데이터를 삭제하지 않는다. |
| ANSI mode `4` / `20`, `DECRQM` | 여러 mode 인자를 처리하며 삽입·개행 모드 상태를 보고한다. 개행 모드는 LF·VT·FF에 CR을 포함하고 일반 Enter·Ctrl+M·비브래킷 붙여넣기를 CRLF로 보낸다. application keypad와 kitty 키보드의 별도 인코딩, 브래킷 붙여넣기 원문은 유지한다. |
| `CSI ! p` / `ESC c` | soft reset은 화면·이미지·탭·마우스·붙여넣기·개행 모드를 유지하며 커서 속성·원점·여백·삽입 모드를 초기화한다. 전체 reset은 기본 화면으로 돌아가 두 화면과 스크롤백을 지우고 입력 모드·그래픽·탭·문자 속성을 초기화한다. |

이전 Termux 동작과 달리 대체 화면 세 모드를 구분하고, 상대 커서 이동은 여백을 넘지 않으며, 역방향 줄바꿈은 프로그램의 `?45h` 요청을 따른다. 커서 질의의 기본 페이지 번호도 xterm 형식에 맞춘다. 이는 화면 프로그램이 표준 제어 시퀀스로 상태를 관리하도록 하는 호환성 수정이다.

위 동작의 저장 커서·줄바꿈 대기·개행 모드·모드 캐시도 전체 체크포인트에 포함한다. Android 전환 자체가 모드 초기화나 대체 화면 전환을 실행하지 않는다. 실제 크기 변경은 기존 resize/reflow 규칙을 따른다.

지원 범위는 위 제어 시퀀스와 기존 에뮬레이터 기능이다. VT52, 모든 VT420/xterm 확장, 실제 80/132열 전환이나 셸의 Android 창 크기·전체 화면 조작까지 구현한 것은 아니다. 기존 앱 크기 정책과 기능 질의 응답 범위는 유지한다.

## 화면과 출력 구독의 소유권

`UnifiedSessionRegistry`의 `RuntimeTerminalState`가 `:vmhost` 서비스 프로세스에서 세션 생성 직후부터 터미널 상태를 유지한다. Native·Rootless 출력과 AVF 스트림을 UI 존재 여부와 관계없이 해석하고, 커서 위치·색상·기능 질의에 한 번만 응답한다. 프로필 또는 전역 팔레트는 첫 출력 전에 적용한다. PTY·VM 소유권은 기존 런타임 컨트롤러에 남는다.

`TerminalSessionStore`는 Application에 속한 UI 미러다. Activity가 보이지 않으면 5초 동안 미러와 출력 구독을 유지한다. 유예 시간 안에 돌아오면 기존 미러를 그대로 사용한다. 이후에는 UI 미러·체크포인트·출력 구독과 UI의 서비스 바인딩을 해제하여 중복 파싱을 중단한다. runtime·PTY·authoritative emulator는 계속 출력을 처리하며 세션 종료나 runtime stop은 실행하지 않는다. 복귀하면 새 미러에 runtime checkpoint와 저장된 viewport를 설치하므로 이전 구독의 늦은 출력이 새 화면에 섞이지 않는다. 새 UI 프로세스나 재연결은 원시 출력 재생 대신 런타임의 완전한 체크포인트를 설치한다. 기본·대체 버퍼와 설정된 범위의 스크롤백, 셀 문자·폭·줄바꿈·스타일·밑줄 색상, 현재·저장 커서, 여백·탭·문자 집합, 입력·마우스·키보드 모드와 스택, 제목·팔레트, 미완성 UTF-8·CSI·OSC·DCS·APC, 진행 중 이미지 전송·SIXEL·kitty 이미지·애니메이션·배치를 전달한다. UI의 출력·클라이언트 바인딩은 전송하지 않는다.

출력 바이트는 기존 순서와 backpressure를 그대로 유지한다. UI 변경 알림만 Choreographer frame 단위로 합쳐 terminal redraw·접근성·chrome 갱신을 줄인다. kitty/SIXEL 애니메이션 redraw는 실제로 보이는 TerminalView에서만 예약하며 창 숨김·뷰 숨김·detach 시 예약된 callback을 제거한다. 숨겨진 탭의 미러는 렌더링 타이머를 소유하지 않는다.

## 복원 순서와 화면 위치

`attachTerminal`은 동일한 잠금 안에서 셀 크기·팔레트를 맞추고 특정 구독자에게 체크포인트를 전달한 뒤 실시간 출력·크기 변경·종료를 이어 전달한다. 구성·연결·뷰포트 명령은 서비스의 순서 있는 대기열을 사용한다. 체크포인트를 설치하기 전의 출력이나 다른 구독자의 과거 출력 재생을 미러에 섞지 않는다. UI는 받은 객체를 기존 에뮬레이터에 설치하므로 View의 참조와 출력 바인딩을 유지한다. 크기 변경도 서비스와 미러에서 동일한 출력 경계에 적용한다.

체크포인트는 같은 앱 빌드 사이의 호스트 전용 `TSC1` 형식이다. 허용된 터미널 상태 클래스만 읽으며 프록시를 거부한다. 큰 스크롤백과 이미지는 Binder 바이트 배열 대신 익명 파이프의 읽기 전용 파일 디스크립터로 전달한다. 서비스는 상태 잠금을 보유한 동안 별도 writer에서 전송하며, 데이터 파일이나 게스트가 열 수 있는 경로를 만들지 않는다. 게스트 프로토콜과 파일에는 이 역직렬화 경로를 제공하지 않는다.

`TerminalSession`은 체크포인트·크기 변경 메시지와 제한된 출력 큐의 메시지를 한 순서로 처리한다. 실시간 미러 해석은 조회 응답만 억제하고 현재 출력의 벨·클립보드 이벤트는 유지한다. 체크포인트 복원은 과거 이벤트를 실행하지 않는다. 이전 컨트롤러의 `replay=true` 프레임은 호스트 진단·기존 구독자용으로 남지만 앱 화면 복원의 근거로 사용하지 않는다.

각 탭에서 읽던 기록의 행·행 내부 오프셋·기록 이동 카운터도 서비스에 저장한다. 새 출력과 같은 열 수에서의 화면 높이 변경은 저장된 위치를 새 기록 좌표로 보정한다. 대체 화면·프로그램 마우스 스크롤은 프로그램이 뷰포트를 결정하고, 일반 셸은 저장된 기록 위치를 유지한다. 열 수가 달라지면 기존 터미널 reflow 규칙을 적용한다.

같은 너비로 대체 화면의 높이만 늘어나면 기존 행을 재배치하지 않고 행 저장 공간을 확장한다. 키보드 숨김·복귀 때 기존 빈 셀의 배경·문자 스타일·밑줄 색상도 보존한다. 실제 열 수 변경은 기존 reflow 규칙을 적용한다.

`2026` 동기화 출력의 진행 상태와 남은 마감 시간을 보존한다. 새 View도 배치 완료 또는 기존 마감 전에는 미완성 프레임을 표시하지 않는다. 애니메이션은 렌더러가 없는 시간도 프로토콜 명령 처리 시 반영한다. PTY의 마지막 출력이 배출된 뒤 종료 이벤트를 전달하므로 종료 직전 출력도 보존한다.

프로그램이 종료되어도 보관된 화면의 크기 변경 구독은 명시적인 UI 분리까지 유지한다. 새 체크포인트 뒤에는 런타임의 종료 상태도 전달하므로 종료 메시지와 화면을 다시 표시한다.

## 복원 보장 범위

Activity 재생성·파괴·재진입과 UI 프로세스 종료 후의 재연결은 런타임이 유지한 터미널 전체 상태를 복원한다. 원시 출력의 8 MiB 제한 밖으로 초기 제어 시퀀스가 밀려나도 복원이 깨지지 않는다. 보장은 설정된 스크롤백 및 기존 이미지 저장 한도 안에서 유지되는 상태에 적용된다. 체크포인트 때문에 무제한 출력 기록이나 이미지 저장소를 만들지 않는다.

전제는 런타임 서비스와 실제 PTY·VM이 살아 있다는 것이다. 앱 전체 강제 종료나 런타임 소유 프로세스·VM 종료로 사라진 작업을 복구했다고 표시하지 않는다. AVF의 여러 세션 상태를 유지하려면 모든 실행 중 PTY를 수집하도록 갱신된 게스트 `droid-sessiond`가 필요하다. 실제 AVF 전송 자체가 끊긴 동안의 출력 보존은 별도의 게스트 전송 보장 범위다.

원시 기록을 더 크게 저장하는 방식은 초기 모드·파서·이미지가 기록 한도 밖으로 밀려나는 문제를 해결하지 못하므로 선택하지 않았다. 서비스 상태와 UI 미러를 각각 보관하는 메모리 비용을 감수하고 완전한 체크포인트를 사용한다. 저장 한도는 기존 스크롤백·이미지 정책을 유지한다.

## 회귀 검증

- `TerminalCheckpointTest`: 전체 상태와 객체 참조 관계의 복원, 모든 바이트 경계의 미완성 UTF-8·CSI·OSC·DCS·이미지 전송, 미러 조회 응답 억제와 클래스 허용 목록.
- `VtCompatibilityTest`: 대체 화면 세 모드, 독립 저장 커서·줄바꿈 대기, 모드 캐시, 여백의 이동·편집·질의, 역방향 줄바꿈, ANSI 개행 입력, soft/full reset.
- `RuntimeTerminalStateTest`: 9 MiB 출력 이후 기본·대체 화면과 모드 복원, UI 없는 질의 응답, 체크포인트·출력·크기·종료의 순서, 뷰포트와 팔레트 보존.
- `TerminalProcessDeathProbe`(debug 전용, DUMP 권한 필요): UI 프로세스만 실제 종료한 뒤 셀별 기본·대체 화면·기록·스타일, 색상·이미지·마우스 모드와 중단된 파서의 재연결 검증.
- `ProgramScrollTest`: Codex 포인터 모드 조합, 상호 배타적 마우스 모드, SGR 휠·이동·숫자 로캘, 대체 스크롤 질의·복원, 포커스 보고.
- `SynchronizedOutputTest`: 같은 크기로 다시 연결했을 때 `2026` 배치 유지.
- `ResizeTest`: 대체 화면의 높이를 확대·복귀한 뒤 빈 셀의 스타일·밑줄 색상과 기존 행 보존.
- `TranscriptScrollCounterTest`: 화면 높이 변경과 대체 화면 복귀에 따른 기록 좌표 보정.
- `ProgramScrollDeviceTest`: Android 휠 축·잔여량·대체 스크롤, 실시간/재생 출력의 순서 및 조회 응답 억제, 종료된 화면의 크기 변경 구독, View의 Enter·Ctrl+M·붙여넣기 개행 모드.
- `TerminalTuiLifecycleTest`: 별도 Native PTY에서 백그라운드 출력, Activity 재생성, 끊긴 CSI 파서, 복귀 후 휠 입력, Activity가 없는 동안의 출력과 스크롤 고정, 대량 출력 중 구독 등록, 새 미러의 체크포인트 복원 후 fullscreen 종료 시 셸 내용·커서·색상 복원.
- 기존 `SessionScrollPositionTest`, `ProfilePaletteTest`, `WrappedShellInputTest`: 기록 위치·프로필 팔레트·PTY 크기 동작의 회귀 확인.

이 테스트들은 프로그램이 사용하는 터미널 프로토콜과 수명 경계를 검증한다. Codex 계정의 실제 대화 또는 Neovim의 모든 플러그인 동작을 검증한 결과로 해석하지 않는다.

실제 UI 프로세스 종료 검증은 debug APK에서 `TerminalProcessDeathProbe`에 `prepare` 또는 `prepare-viewport`를 broadcast하고 결과가 `READY`일 때 시작한다. 홈으로 이동한 뒤 `run-as dev.terminal.app.debug kill -9 <UI PID>`로 UI 프로세스만 종료한다. `:vmhost` PID가 유지되는지 확인하고 `SetupActivity`를 실행한 뒤 각각 `verify` 또는 `verify-viewport`의 결과가 `PASS`인지 확인한다. 각 시나리오 뒤에는 `cleanup`을 broadcast한다. 패키지 전체를 종료하는 `am force-stop`은 이 검증과 다르며 사용하지 않는다. 테스트 세션 외의 기존 작업이 있는 기기에 APK를 교체 설치하지 않는다.

### 2026-09-30 검증 결과

- JDK 17에서 저장소의 정식 검증 `./gradlew :android-app:assembleDebug :android-runtime:testDebugUnitTest :image:test --console=plain`을 통과했다. 추가 터미널·앱 모듈의 `testDebugUnitTest`와 테스트 APK 빌드도 통과했다. 해당 단위 테스트 결과는 530개 성공, 1개 생략이며 생략 사유는 `ROOTLESS_TEST_ARCHIVE` 미제공이다.
- Android 15 ARM64 Redroid에서 프로그램 입력·수명 테스트 5개와 기존 회귀 테스트 9개가 성공했다. Rootless 기기 편집 테스트 1개는 이미지 미설치로 생략했다. AVF 특권 통합 및 실제 Codex 계정 대화·Neovim 실행은 수행하지 않았다.
- Android MCP로 기기 정보·APK 설치·로그를 확인하고 Replicant로 기기 회귀 테스트를 실행했다. 긴 Android MCP shell 호출은 60초 제한에 걸렸으므로 기기 TestRunner 로그로 완료 상태를 확인하고 Replicant의 긴 실행 기능으로 최종 테스트 결과를 받았다. 초기 UI 자동화 연결 오류는 동시 UI 조회 없이 재실행해 해소했다.

### 2026-10-01 완전 복원 검증 결과

- JDK 17에서 정식 검증 명령과 터미널 에뮬레이터·View·앱 단위 테스트, 테스트 APK 빌드가 성공했다. 단위 테스트 540개가 성공했고 `ROOTLESS_TEST_ARCHIVE`가 없는 테스트 1개를 생략했다. `cargo test --locked --offline --manifest-path guest/droid-sessiond/Cargo.toml`의 게스트 테스트 16개도 성공했다.
- Android 15 ARM64 Redroid에서 UI 프로세스만 실제 종료한 두 시나리오가 성공했다. 런타임 PID는 유지됐으며 기본·대체 화면의 모든 셀 문자·스타일·줄바꿈과 기록, 팔레트·커서·마우스 모드·이미지 픽셀, 중단된 CSI·OSC 처리와 별도 스크롤 앵커를 확인했다. 체크포인트는 1 MiB를 넘었고 UI가 없는 동안 9 MiB 출력 뒤의 커서 질의가 정상 처리됐다.
- 프로그램 스크롤·Activity 수명·종료된 화면 구독 테스트 6개와 기존 기록·팔레트·Native 셸 편집 회귀 테스트 9개가 성공했다. Rootless 기기 편집 테스트 1개는 이미지 미설치로 생략했다. AVF 특권 기기 통합과 실제 Codex 계정 대화·Neovim 실행은 수행하지 않았다.
- 초기 기기 검증에서 발견한 대체 화면 높이 확장 시 빈 셀 스타일 변경을 수정하고 셀별 비교를 다시 통과했다. 출력 생성용 외부 `printf` 프로세스를 12,000번 실행하던 테스트는 같은 출력량의 mksh 내장 `print`로 바꿨으며 기존 15초 제한 안에서 통과했다. 대용량 체크포인트 준비는 출력 전달 완료를 기다리므로 준비 중인 프레임을 누락으로 판정하지 않는다.
- Android MCP로 설치·로그·프로세스 종료와 재실행을 확인했고 Replicant로 기기 회귀 테스트를 실행했다. Context7의 reliable-pipe 문서 조회는 일치하는 자료를 반환하지 않아 [Android 공식 ParcelFileDescriptor 문서](https://developer.android.com/reference/android/os/ParcelFileDescriptor)로 API와 읽기·쓰기 끝의 계약을 확인했다. 외부 이슈·연결 앱 작업이 없어 codex_apps는 사용하지 않았다.

### 2026-10-01 VT/xterm 호환성 검증 결과

- JDK 17에서 정식 검증 `./gradlew :android-app:assembleDebug :android-runtime:testDebugUnitTest :image:test --console=plain`과 에뮬레이터·View·앱의 `testDebugUnitTest`, `:android-app:assembleDebugAndroidTest`가 성공했다. 최종 단위 테스트는 562개 성공, 1개 생략이며 생략 사유는 `ROOTLESS_TEST_ARCHIVE` 미제공이다. 에뮬레이터 테스트 249개에는 이번에 추가한 VT/xterm 테스트 21개와 체크포인트 테스트 1개가 포함된다.
- Android 15 ARM64 Redroid에서 프로그램 스크롤·View 개행 입력·Activity 수명·fullscreen 체크포인트 테스트 8개가 성공했다. 별도 Native PTY가 백그라운드에서 원점 상대 커서 질의를 처리한 뒤, 새 UI 미러로 다시 연결하고 대체 화면을 종료했을 때 원래 셸 내용·저장 커서·색상이 복원됐다. Enter·Ctrl+M·붙여넣기의 각 개행 입력은 한 전송으로 전달됐다.
- 기존 기록 위치·프로필 팔레트·Native 셸 편집 기기 회귀 9개가 성공했다. Rootless 편집 테스트 1개는 이미지 미설치로 생략했다. AVF 특권 통합과 실제 Codex 계정 대화·Neovim 실행은 수행하지 않았다.
- 실제 UI 프로세스만 종료한 전체 화면·일반 기록 위치 시나리오도 다시 성공했다. 런타임 PID가 유지됐으며 9 MiB 백그라운드 출력과 1 MiB를 넘는 체크포인트에서 두 버퍼·기록·셀 스타일·색상·이미지·입력 모드·미완성 CSI·OSC 및 읽던 기록 앵커를 복원했다. 새 개행 모드와 Enter 인코딩도 복원 확인에 포함했다.
- 첫 기기 실행에서 새 미러의 비동기 체크포인트 설치 전에 검사해 실패한 테스트는 설치 완료를 기다리도록 수정한 뒤 다시 통과했다. 기존 여백 테스트는 xterm의 영역 안·밖 커서 동작을 구분하는 입력으로 갱신했다.
- Android MCP로 APK 설치와 실제 프로세스 종료·복귀를 검증했고 Replicant로 기기 입력·수명 회귀를 실행했다. Context7 조회를 공식 xterm 문서·원본 코드로 대조했으며, VT510 문서 조회 실패는 해당 공식 자료로 보완했다. 연결된 외부 앱 작업이 없어 codex_apps는 사용하지 않았다.

## 배터리 최적화 검증 (2026-10-03)

- canonical `:android-app:assembleDebug :android-runtime:testDebugUnitTest :image:test`와 추가 `:android-app:testDebugUnitTest :terminal-view:testDebugUnitTest`가 통과했다. runtime 단위 테스트는 230개 성공·외부 archive fixture가 필요한 1개 생략, 앱 117개·image 4개·terminal-view 14개가 성공했다.
- Android 15 ARM64 redroid에서 idle prompt의 실제 partial wake lock 해제와 조용한 command의 background 유지·종료 해제, 5초 유예 내 미러 재사용과 이후 구독 해제, PTY·sibling 보존, 기본·대체 화면·미완성 CSI·scrollback·graphics·viewport 복원, 출력 폭주·재생성 및 polling STOP/START를 검증했다. 관련 기기 검사 14개가 성공했고 Rootless graphics 1개는 이미지 미설치로 생략했다.
- 최초 instrumentation 호출의 timeout으로 UiAutomation 연결이 끊긴 문제는 기기에서 검사 프로세스를 독립 실행하고 결과를 읽는 방식으로 해결했다. 기존 viewport fixture는 runtime checkpoint와 경합하는 UI 미러 직접 수정 대신 실제 PTY 출력을 사용하도록 바꿨고, toolbar fixture는 키보드 숨김을 BACK 탐색 입력과 분리했다.
- Android MCP는 target 확인·APK 설치·instrumentation·`dumpsys power` 증거를 제공했다. Replicant는 UI 상태·탐색을 확인했다. Replicant의 기본 launch 실패와 non-exported MainActivity 직접 시작 실패는 실제 exported SetupActivity로 우회했다. Context7은 Android View frame callback·취소 API를 확인했으며 외부 앱 저장소 작업이 없어 codex_apps는 사용하지 않았다.
- AVF 특권 guest/bridge 실연결과 실제 배터리 소비량 측정은 수행하지 않았다. backoff 단계·reset·대기 중 reset·무작업 retry 중단 및 AVF transport 교체 시 sibling/checkpoint 보존은 단위 테스트로 검증했다. 명령 경계 신호가 없는 셸의 10분 lease 한계는 RUNTIMES와 사용자 가이드에 명시했다.
