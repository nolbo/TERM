# Compose Material 3 design system

## Direction

Droid Terminal의 활성 Android UI는 Jetpack Compose와 안정 Material 3 컴포넌트로 구현한다. 앱 크롬은 시스템의 밝은/어두운 테마를 따르고 Android 12 이상에서는 동적 색상을 사용한다. 동적 색상을 사용할 수 없을 때는 민트 계열 primary와 중립 surface를 갖는 명시적 밝은/어두운 색상표를 적용한다.

터미널 캔버스는 예외다. `TerminalView`와 `TerminalAppearance`가 사용자 선택 팔레트, ANSI 색상, 커서, 선택 영역과 배경을 독립적으로 관리하며 Material 또는 동적 색상이 이를 덮어쓰지 않는다.

## Foundations

- 색상은 `MaterialTheme.colorScheme`의 `primary`, `onPrimary`, `surface`, `surfaceContainer`, `onSurface`, `outline`, `error` 같은 의미 역할로만 사용한다.
- 앱 텍스트는 Material 3 type scale을 사용한다. 고정폭 글꼴은 터미널, extra key, 진단 및 크래시 payload에만 사용한다.
- 간격은 4 dp 배수를 기본으로 하며 표준 Material shape를 사용한다.
- 모든 일반 상호작용 대상은 최소 48 dp다. 두 줄 extra key도 각 셀의 전체 높이를 48 dp 이상 유지한다.
- 아이콘 버튼에는 동작을 설명하는 content description을 제공한다. 장식 아이콘에는 중복 설명을 제공하지 않는다.
- 화면 전환에 별도 View transition을 강제하지 않는다. Compose 및 플랫폼의 애니메이터 배율을 따르는 표준 컴포넌트 상태 변화만 사용한다.

구현은 `android-app/src/main/kotlin/dev/terminal/app/ui/ComposeUi.kt`의 `DroidTheme`와 공통 scaffold/화면 컴포넌트에 모인다. 사용자 문구는 `android-app/src/main/res/values/strings.xml`에 둔다.

## Components and screens

초기 설정은 Native, Rootless Debian, AVF Debian을 각각 카드로 설명하고 즉시 터미널 시작과 AVF 로컬 이미지 선택을 분리한다. 세로 스크롤을 사용하므로 좁은 화면, 가로 화면, 큰 글꼴에서도 작업 버튼에 접근할 수 있다.

터미널은 다음 세 영역을 유지한다.

1. 가로 스크롤 세션 선택 영역과 고정된 새 세션/설정 아이콘 버튼
2. `AndroidView`에 포함된 기존 `TerminalView`
3. 각각 최소 48 dp인 두 줄 extra key

선택 세션은 체크 표시와 “현재 세션” 텍스트를 함께 보여 색만으로 상태를 전달하지 않는다. 종료 아이콘은 확인 대화상자를 연 뒤에만 runtime의 명시적 terminate 명령을 보낸다. 새 세션과 Rootless 준비 상태는 Material 3 대화상자, 진행 표시기, 오류 텍스트, 재시도 및 닫기 동작을 사용한다.

설정 개요와 상세는 공통 앱 바, `ListItem`, `Switch`, `OutlinedTextField`, 단일 선택 `AlertDialog`/`RadioButton`을 사용한다. boolean 행 전체가 한 번만 toggle되며 입력 오류는 supporting text로 설명한다. 진단과 크래시 payload는 선택 가능한 고정폭 텍스트로 유지한다. 크래시 화면에서는 복사를 primary action으로, 닫기를 낮은 강조 action으로 제공한다.

## Insets and adaptive behavior

모든 화면은 edge-to-edge다. 공통 앱 바와 터미널 chrome은 status bar inset을 적용하고, 하단 extra key는 navigation bar inset을 적용한다. 터미널 화면은 IME inset을 반영해 키보드가 terminal/extra key를 가리지 않게 한다. 목록 기반 화면은 고정 텍스트 높이를 사용하지 않고 스크롤한다.

상태 표시줄과 탐색 표시줄 아이콘 명암은 현재 Compose 밝은/어두운 색상표에 맞춰 갱신한다. RTL에서는 start/end 기반 배치를 사용하되, 문자 셀 정확성이 필요한 터미널 렌더러 자체는 기존 방향 규칙을 유지한다.

## Lifecycle boundary

Compose 상태는 서비스 상태의 투영일 뿐 권위 있는 세션 저장소가 아니다. Activity가 시작되거나 서비스에 재연결되면 세션 목록과 runtime 상태를 다시 읽는다. `AndroidView` 재구성은 `TerminalView`를 불필요하게 만들거나 세션을 종료하지 않으며, Activity 정지는 renderer callback을 detach하고 로컬 emulator projection만 폐기한다.

PTY, 프로세스, Rootless 설치와 VM은 계속 `:vmhost` runtime 서비스가 소유한다. 대화상자 닫기, 세션 전환, Activity 재생성 또는 UI task 제거는 terminate 명령이 아니다. 실제 종료는 사용자가 확인한 세션 종료 동작을 runtime이 처리할 때만 일어난다.
