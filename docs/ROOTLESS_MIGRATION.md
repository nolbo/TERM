# 기존 Rootless 설치를 새 가속 형식으로 이전

## 사용 방법과 범위

2026-09-20 가속 통합 변경부터 채널 CI는 가속+stdio+rename 입력을 고정해 빌드한다.
이전 일반 dev 배포 `951bc7b1bf71`은 가속을 켜지 않았으며 이전 버튼도 표시하지 않는다.

가속 기능이 포함된 APK로 업데이트한 뒤 **설정 → Rootless Linux → 해당 배포판 →
가속 형식으로 이전**을 누른다. 앱의 모든 터미널 세션과 공유 작업 폴더의 파일 작업을
먼저 마쳐야 한다. 앱이나 배포판을 삭제·재설치하지 않는다. UI는 세션을 강제로 종료하지 않는다.

대상은 배포판 rootfs, 해당 배포판의 별도 홈, 앱의 공유 workspace다. 설치 패키지와
일반 파일을 그대로 유지하며, PRoot의 두 단계 에뮬레이션 링크를 실제 하드링크로
변환한다. 네이티브 형식 표시를 쓴 뒤 새 세션부터 가속 DSO와 해당 실행 프로필을
적용한다. 이미 네이티브 형식이면 다시 변환하지 않는다. 단순 업데이트만 한 경우에는
이전의 호환 실행 모드를 유지하며, 사용자가 이 동작을 선택해야 실제 변환한다.

기기에서 하드링크 생성이 거부되면 이전할 수 없다. 표시 파일만 바꿔 제한을 우회하지
않으며 기존 설치를 보존하고 오류를 알린다. 가속 형식으로 바뀌어도 모든 작업에 같은
속도 향상을 보장하지 않으며, 순차 I/O 전체 2배 목표 달성을 뜻하지 않는다.

## 변환과 복구

1. 서비스가 모든 런타임의 세션 생성 잠금을 잡고, 실행 중인 세션과 상태 확인 실패가
   없는지 확인한다. 대상 컨트롤러의 NSS 캐시도 종료한다.
2. 별도 작업 디렉터리에서 실제 하드링크 지원을 검사한다.
3. rootfs·홈·workspace를 심볼릭 링크를 따라가지 않고 조사한다. 숨겨진 중간 링크,
   데이터 파일 이름과 기록된 링크 수, 모든 링크 대상이 범위 안에 있는지 확인한다.
   Android의 `/data/user/0` 경로 별칭은 실제 부모 경로로 대응한다. 읽기·탐색 권한이 없는
   디렉터리는 원래 권한을 별도 기록으로 동기화한 뒤 소유자 읽기·탐색 권한만 임시로
   부여한다. 트리 교체 전 또는 실패 복구 시 원래 권한으로 되돌린다.
4. 새 디렉터리들을 준비한다. 일반 파일 데이터는 원래 inode에 하드링크하여 유지하며,
   에뮬레이션 링크만 같은 데이터 inode를 가리키는 실제 하드링크로 만든다. 일반 심볼릭
   링크는 유지하고, 디렉터리 권한(sticky/setgid 포함)·수정 시각을 보존한다. 검증한 에뮬레이션의
   숨겨진 중간 링크와 데이터 보관 이름은 새 트리에서 제외한다.
5. 새 루트의 네이티브 링크 지원을 재검증하고 `native-hardlinks-1`을 기록한다.
   디렉터리와 기록을 동기화한 뒤, 원래 트리를 백업 이름으로 이동하고 새 트리를
   활성 위치로 이동한다. 세 트리를 모두 교체한 뒤에만 완료 기록을 남긴다.
6. 완료된 뒤 이전 트리의 디렉터리 항목과 작업 기록을 정리한다. 파일 데이터 전체의
   별도 사본을 만들지는 않지만, 새 디렉터리·링크 항목을 위한 여유 공간은 필요하다.

작업 기록은 게스트 루트 밖에 있으며 `STAGING`, `PUBLISHING`, `COMMITTED` 단계를
구분한다. 일반 실패는 즉시 이전 트리로 복구한다. 중단된 작업 기록이 있으면 새 세션과
재설치·삭제를 차단한다. 설정에서 **같은 배포판의 이전 버튼**을 다시 누르면 미완료
교체를 복구한 후 다시 시도하거나, 이미 완료된 이전의 정리 작업을 끝낸다.

구조가 불분명한 링크, 다른 배포판·외부 저장소를 향한 에뮬레이션 링크, 링크 수 불일치,
소켓/FIFO/장치 같은 특수 파일은 임의 변환하지 않고 오류로 중단한다. 미확인 데이터를
삭제하거나 새 설치로 대체하지 않는다. 모든 외부 파일 작업까지 OS 수준에서 정지시키는
기능은 아니므로, 파일 관리자나 외부 자동화가 workspace를 수정 중이면 먼저 종료해야 한다.

## 검증

- `RootlessNativeMigrationTest`: rootfs·홈·workspace 사이의 실제 inode 공유, 하드링크 수,
  링크를 통한 쓰기, 일반 링크·파일·읽기 전용 디렉터리 보존, Android식 경로 별칭,
  하드링크 거부·누락된 링크, 모든 교체 경계의 실패/중단 복구, 재실행을 검사한다.
- `UnifiedSessionRegistryTest`: 다른 런타임의 활성 세션 및 상태 확인 실패 시 거부,
  모든 런타임의 새 세션 생성 배제, 미완료 기록에 따른 실행 차단을 검사한다.
- `RootlessNativeMigrationDeviceTest`: Android 실제 저장소에서 교체 경계마다 중단을
  주입하고 원본 복구 또는 완료 후 정리, 재시도 변환을 확인한다.
- `RootlessFreshInstallTest`: 격리된 구버전 형태의 배포판을 만들고 기존 패키지 DB의
  SHA-256을 비교한다. 홈·workspace 링크를 실제 하드링크로 변환한 뒤 유지보수 명령,
  가속 라이브러리 카운터, 선택적 stdio, 권한·tar·PTY 실행을 검사한다.

실제 변환 시험 인자:

```text
-e class dev.terminal.runtime.rootless.RootlessNativeMigrationDeviceTest,dev.terminal.runtime.rootless.RootlessFreshInstallTest
-e rootlessFreshInstall true
-e rootlessLegacyMigration true
-e rootlessNativeMigration true
-e rootlessDistribution UBUNTU
```

이는 사용자 앱을 건드리지 않는 격리 재현 시험이다. 실제 제품 APK의 업그레이드 설치,
물리 휴대폰 전체, Arch의 기기 실행, 실제 전원 차단은 검증하지 않았다. 동기화와 복구
절차가 있어도 모든 저장장치의 전원 장애 내구성을 시험한 것으로 해석하지 않는다.

## 실제 가속 이전 검증 기록 (2026-09-20)

- 가속+stdio 구성의 정식 빌드와 unit/image 검사 통과. runtime 159개 중 158개 통과,
  기존 선택적 아카이브 시험 1개 생략, image 4개 통과. 최초 서비스 연결 빌드의 누락된
  `File` 참조는 수정했다. 디렉터리 속성·동기화·복구 소유자 보완 뒤에도 재검증했다.
- Debian 변환 통합 시험: `OK (1 test)`, 101.315초. 패키지 DB SHA-256 보존,
  `NATIVE_MIGRATION_VERIFIED`, `STDIO_MAINTENANCE_VERIFIED`, PTY 검증 통과.
- Ubuntu 변환 통합 시험: `OK (1 test)`, 316.762초. 패키지 DB SHA-256 보존,
  `NATIVE_MIGRATION_VERIFIED`, `STDIO_MAINTENANCE_VERIFIED`, PTY 검증 통과.
  이 시간은 설치·패키지 다운로드까지 포함한 시험 시간이며 성능 수치가 아니다.
- Debian 통합 시험 이후 접근 제한 디렉터리 복구를 보완했고 Ubuntu에서 검증했다.
  Ubuntu 통합 시험 이후 추가한 특수 권한(sticky/setgid) 보존은 최종 단위 검사와
  Android 중단·복구 시험(`OK (1 test)`, 0.842초)에서 검증했다.
- 첫 Android 변환은 데이터 경로 별칭 검사에서 원본 교체 전에 실패했다. 부모 경로
  별칭 처리와 회귀 검사를 보완한 뒤 Debian에서 통과했다.
- 첫 Ubuntu 변환은 `var/lib/snapd/void`의 접근 제한 때문에 원본 교체 전에 실패했다.
  임시 디렉터리 권한과 지속 복구 기록을 추가했고, 중첩 권한 000 디렉터리의 데이터·권한
  보존 및 탐색 중단 복구 시험을 추가했다. 수정 시각 복원 순서와 권한 000 경로의
  chmod 방식에서 발견한 단위 시험 실패도 수정했다.
- Ubuntu 재실행 한 번은 설치 전 4 GiB 여유 공간 검사에서 중단됐다. 이번 작업의
  별도 UI 시험 앱만 제거하여 공간을 확보했으며 사용자 앱·배포판은 변경하지 않았다.
- Android의 디렉터리 접근 권한 변경 및 모든 교체 경계 중단 주입 시험 통과. 이 시험은 중단 지점에서 자동
  복구를 생략한 후 다음 호출의 복구를 검증하며, 실제 전원 차단 시험은 아니다.
- 별도 UI 시험 패키지에서 replicant로 설정의 이전 버튼을 눌렀다. Android MCP로
  원래 두 심볼릭 링크가 같은 inode, 실제 링크 수 2의 일반 파일로 바뀌고 내용이
  보존되는 것을 확인했다. 완료 후 이전 버튼이 사라졌다. 사용자 앱은 교체하지 않았다.
- UI 시험 앱은 저장소 빌드 설정을 바꾸지 않고 임시 Gradle init의 `finalizeDsl`로
  다른 applicationId를 사용했다. Context7에서 적절한 AGP 문서를 찾지 못해
  [공식 AGP 확장 문서](https://developer.android.com/build/extend-agp)를 확인했다.
  replicant 앱 실행은 기기 사용자 선택 문제로 실패해 Android MCP의 명시적 user 0
  실행으로 대체했고, 중복 UI 선택자는 조회한 좌표로 조작했다.

아래는 이전 구현의 **호환 실행만** 확인한 기록이다. 실제 가속 형식 변환의 증거와
구분하여 보존한다.

## 2026-09-20 검증 기록

다음 빌드·단위 테스트가 통과했다. runtime 149개 중 148개 통과, 기존 선택적
아카이브 시험 1개 생략, image 4개 통과. 일반 프로필의 변경 부위 집중 시험도 통과했다.

```sh
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64 ./gradlew \
  :android-app:assembleDebug :android-runtime:testDebugUnitTest :image:test \
  :android-runtime:assembleDebugAndroidTest \
  -ProotlessAcceleratedPayload=/tmp/terminal-stdio-app-payload \
  -ProotlessStdioBuffer=true --console=plain
```

Android API35 ARM64 시험 환경에서 Android MCP로 별도 시험 APK를 설치했다.
Debian 구버전 재현과 저장 형식 판별은 `OK (2 tests)`, 93.762초로 통과했다.
Ubuntu 구버전 재현도 `OK (1 test)`, 291.598초로 통과했다. 두 배포판 모두
`LEGACY_MIGRATION_VERIFIED`가 기록됐고 임시 설치는 시험 종료 시 정리됐다.
이는 성능 측정이 아니라 통합 시험의 총 실행 시간이다.

첫 Debian 실행은 PTY 시험이 비활성화된 네이티브 가속 심볼을 요구하여 시간
초과로 실패했다. 호환 모드에서는 DSO 부재를 검사하도록 수정했으며, 앞으로
PTY가 실패 종료하면 출력과 함께 즉시 실패하도록 시험도 보완했다. fchmodat2,
권한·tar·PTY 검사는 제거하지 않고 재실행에서 통과했다.

증분 APK 설치 미지원으로 도구가 스트리밍 설치에 성공했다. 사용자 앱을 교체하거나
기존 사용자 배포판을 변경하지 않았다. UI 변경·새 외부 API·외부 서비스 작업이 없어
replicant/context7/codex_apps는 사용하지 않았다. 실제 제품 APK 업데이트 및
Arch/물리 휴대폰 검증은 수행하지 않았다.
