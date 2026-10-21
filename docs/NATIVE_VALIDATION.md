# Native Shell 검증 기록 — 2026-10-21

아래는 `dev` 작업 트리의 Native 구현 검증 단계 기록이다. 이 단계에서는 커밋·푸시·서명·릴리즈 게시를 수행하지 않았다. 후속 배포는 [1.2.0 출시 기록](release-validation/1.2.0.md)에 구분한다.
구현/빌드 계약은 [NATIVE_SHELL.md](NATIVE_SHELL.md)를 따른다.

## APK 비교

과거 main APK는 비교에서 제외했다. 변경 전 기준은 **dev
`36bfa1021b9a`의 커밋된 소스**를 별도 임시 디렉터리에 읽기 전용으로 풀어
같은 Gradle wrapper·SDK·JDK21·unsigned Release 설정으로 새로 빌드한 APK다.
작업 후 APK는 그 소스에 현재 작업 트리의 Native 변경을 적용한 결과다.
두 APK 모두 `1.1.0+dev.712.36bfa1021b9a` 메타데이터를 가지므로 아래 SHA-256으로
산출물을 구분한다.

| 항목 | 바이트 | MB (10⁶바이트) |
| --- | ---: | ---: |
| 변경 전 dev 기준 APK | 15,482,520 | 15.48 |
| 변경 후 unsigned Release APK | 26,707,020 | 26.71 |
| 증가분 | 11,224,500 | 11.22 |

증가율은 72.50%다. 아래는 **APK 안에서 압축된 추가
파일의 용량**이며 ZIP 디렉터리/헤더, manifest, 라이선스 및 Kotlin 변경분은 별도다.
공용 OpenSSL은 도구별로 중복 계산하지 않는다.

| 구성 | 실제 실행 검증 | 압축된 추가 용량 MB |
| --- | --- | ---: |
| jq 1.8.2 | JSON 변환·필터·오류 종료 코드 통과 | 0.29 |
| curl 8.23.0 | HTTP 다운로드·기본 내장 CA 공인 HTTPS·신뢰 체인/호스트명 거부 통과 | 0.49 |
| OpenSSH 10.6p1 | 키 인증·HOME/known_hosts·잘못된 키/호스트 거부·로컬 포워딩 통과 | 0.52 |
| rg 15.2.0 | 파일 검색·정규식·파일 목록 통과 | 1.57 |
| Vim 9.2.1217 | UTF-8·vimrc·문법·저장·37×113 resize·IME/Escape/Enter·Activity 재생성 통과 | 4.97 |
| 공용 OpenSSL 3.5.9 | curl/SSH 공용 암호화/TLS 라이브러리 통과 | 2.83 |

Vim 행은 runtime을, curl 행은 CA bundle을, SSH 행은 `ssh-keygen`을 포함한다.
공통 OpenSSL 사용, strip/섹션 제거, 비필수 curl 프로토콜·Vim GUI/언어 임베딩·
도움말/번역/사전/시험 자료/생성기·Git 전용 문법 파일 제외로 용량을 줄였다.
필수 netrw 및 선택 terminfo는 유지했다. Git 실행 파일·Git 전용 의존성은 추가하지 않았다.

- 변경 전 APK SHA-256: `59047c3dfe30833b04c156bddd3c480b90114f04e3df6dc31324f37b37e5eeb9`
- 변경 후 APK SHA-256: `14579de05cf0de6188d81ffaf3b666058bfc43e9864599db85ab3ab5ed399bb8`
- 최종 파일: `android-app/build/outputs/apk/release/android-app-release-unsigned.apk`
- 별도 설치 가능한 Debug APK: `android-app/build/outputs/apk/debug/android-app-debug.apk`

## 빌드·정적 검사

- JDK17에서 canonical `:android-app:assembleDebug :android-runtime:testDebugUnitTest
  :image:test` 및 `:android-app:assembleDebugAndroidTest` 통과.
- Runtime 단위 시험 **396개**, image 시험 **65개**, 실패/오류 0.
- `tools/iphone/connector/build.sh ... --test`: host Rust 시험 **7개** 통과
  (Native CLI 프레임/전송 시험 4개 포함).
- JDK21에서 `:android-app:assembleRelease -PterminalUnsignedRelease=true` 통과.
  JDK17 Release lint는 `AndroidSerialConnection.kt` 분석 중 lint 내부의
  `List.removeLast()` `NoSuchMethodError`로 실패했다. 기존 JDK21을 사용했으며
  SDK/NDK/Gradle 버전을 변경하거나 lint를 끄지 않았다.
- ARM64 호스트는 기존 SDK/AAPT2 호환 래퍼를 사용했다. Python 문법 검사와
  `git diff --check` 통과.
- `tools/native-cli/verify.py --apk APK`: 새 ELF 8개의 ARM64/Bionic/16 KiB LOAD
  정렬·SHA-256·동적 의존성과 정확한 runtime 파일 목록 **1848개** 확인.
  라이선스/출처 카탈로그 **123문서**의 원문 해시 확인. Git/testdir 잔존 없음.
- 생성물 전체를 보존해 옮긴 뒤 압축 소스만 재사용하여 같은 경로·고정 toolchain에서
  새로 컴파일했다. **8개 ELF 모두 바이트 SHA-256 동일**, runtime resource manifest도
  동일했다. Rust/NDK 런타임 법적 고지 추가는 그 동일 ELF를 보존한 데이터 변경이다.

## Android 실제 실행

연결된 ARM64 Android 15(API35), 4 KiB 페이지 환경에서 다음 시험이 각각 통과했다.

- `NativeCliDeviceTest`: 다섯 도구의 실행 진단, 잘못된 캐시 저장소의 오류 보고와
  기존 파일 보존, APK 승인 명령 링크 복구, 사용자 termrc/vimrc, JSON/검색/스크립트,
  파이프/리다이렉션/종료 코드, 메모리 `fc` 기록, OSC133 실패 코드, 세션 정상 종료.
- 로컬 TLS/SSH fixture: 신뢰 CA 성공, 기본 CA의 비신뢰 인증서 거부, 호스트명
  불일치 거부, 키 인증, HOME/known_hosts, 잘못된 키·변경된/없는 호스트 키 거부,
  `ssh -N -L`을 통한 HTTP 포워딩. 별도 시험으로 **기본 내장 CA를 사용한
  `https://curl.se/` HTTPS** 성공.
- 실제 TerminalView IME, 하드웨어 Escape/Enter, Vim UTF-8 저장 및 화면 재생성.
- Native 클립보드의 기본 거부·명시 허용·포커스/런타임/크기 제한.
  시험은 기존 클립보드와 grant를 복원한다.
- `WorkspaceResumeDeviceTest.longBackgroundResumePreservesWorkspaceAndActiveSession`.
- `SplitTerminalDeviceTest.menusTabsDividerWorkspaceAndRecreationPreserveTwoLiveSessions`.
- `UsbBrokerDeviceTest.realSocketRejectsWrongAndExpiredSessionsAndOversizedFrames`.
  오래된 serviceRevision=3 기대값을 현재 구현의 4로 갱신했다.

초기 Vim runtime의 netrw 누락을 수정했다. UI 시험의 `:wq`+줄바꿈을 한 번의 IME
commit으로 보내면 기존 bracketed-paste 규칙이 적용되므로 실제 Enter 키 이벤트로
시험을 바로잡았다. 수정된 해당 시험들은 최종 집중 실행에서 통과했다.
검증용 루프백 서버·키와 이 작업의 reverse 포트 매핑은 정리했다.

## 검증 범위와 제약

- **16 KiB 실제 Android 실행 미수행**: ELF 정렬 검사는 통과했지만 대체 증거로
  취급하지 않는다. 다른 Android 버전/ARM64 기기 및 특수 키보드도 미검증이다.
- 시험 Redroid의 **SELinux는 Disabled**였다. stock enforcing 기기의 writable-data
  실행 거부를 실기로 입증하지 않았다. 승인 APK 경로 제한/외부 symlink 거부는
  단위·기기 복구 시험과 코드로 확인했으며 우회 로더·정책 변경은 구현하지 않았다.
- 실제 탈착식 저장소/SAF 파일 전송과 privileged AVF 기기 시험은 미수행이다.
  파일 전송은 같은 브로커의 read/write 승인·프레임·덮어쓰기 방지 로직을 재사용하고
  host 프로토콜 시험 및 실제 세션/크기 거부 시험으로 확인했다. Rootless/AVF 실행
  경로·이미지·데이터 구조는 변경하지 않았다.
- AOSP Android 15 mksh는 공식 빌드의 `HAVE_PERSISTENT_HISTORY=0` 때문에 영구 셸
  기록을 저장하지 않는다. 메모리 기록과 TERM 명령 기록 도구, 또는 Userspace
  Linux/Bash가 대안이다. 기존 파일을 보존하고 영구 기록을 가장하는 코드는 없다.
- Native CLI 클립보드는 허용된 전경 복사만 지원한다. 읽기는 기존 터미널 붙여넣기
  UI를 사용한다. `emitted`는 OSC52 전송이며 복사 완료 응답은 아니다.
- 교차 프로세스 lock 자체의 장시간 스트레스 시험은 수행하지 않았다.

## MCP와 검토

Android MCP는 기기/설치/네트워크 포워딩, replicant는 AndroidJUnitRunner 및 실제
UI 흐름 검증에 사용했다. context7은 월간 한도 초과로 공식 Android/업스트림 자료와
저장소 소스로 대체했다. AVD 목록은 SDK/emulator 설정 오류로 조회할 수 없어 추가
대상을 검증한 것으로 보고하지 않았다. 개별 reverse 해제가 없어 생성한 세 매핑만
로컬 ADB로 제거했다.

Jev 전체 일괄 요청은 공급자 HTTP400으로 실패했다. 축약 gate는 clipboard 주장을
verified(신뢰도 0.92), 원자적 링크 주장을 verified(신뢰도 0.46)로 반환했으나
전체 action=escalate, safe_to_apply=0.16으로 **자동 승인하지 않았다**. 구체적 결함
내용은 제공되지 않았다. FileChannel의 공유 lock 범위 안에서 모든 링크를 갱신하고
ATOMIC_MOVE로 교체하는 코드를 직접 재검토했으며, 기기 복구·권한 시험 및 실제
빌드 결과를 근거로 판단했다. Jev를 증명이나 승인으로 취급하지 않는다.
