# AVF 연결 복구 설계와 검증 기록

작성일: 2026-10-24. 사용자는 TERM 1.4.0에서 공유 디렉토리를 연결한 Ubuntu를
백그라운드에 두었다가 돌아온 뒤 transport disconnected 계열 오류와 터미널 탭 종료를
보고했다. 화면은 잠그지 않았다. 당시 로그가 없어 이 사건의 단일 원인을 확정하지 않는다.

## 관찰과 결정

기존 공유 경로는 host SFTP 작업자 종료 → control EOF → guest broker 실패 →
systemd FailureAction=poweroff로 VM과 모든 세션을 종료할 수 있었다. UI Binder 콜백
정체가 출력 수집을 막는 경로와 Native가 기본 런타임일 때 legacy AVF 재연결을 건너뛰는
경로는 선행 커밋 `d620fa6`, `368d95f`에서 보완했다.

복구 구현 `cdee235bdc1db11f96bfe6fe029e5131bd2c9b94`는 검증된 이미지의
`shareBrokerVersion=2`, `sessionResumeVersion=1`을 통해서만 새 동작을 사용한다.
Kotlin/Python의 bounded duplex byte replay가 같은 프로세스 세대의 통신 스트림을
유지하고 raw vsock만 교체한다. 수신/소비 오프셋을 구분하고 각 방향의 256 KiB 한도를
소비 ACK로 해제한다. 버퍼 포화는 생산자에 흐름 제어를 적용하며 바이트를 버리지 않는다.

공유에서는 같은 SSHFS 프로세스와 ScopedSftpServer 및 열린 FD를 유지한다. 소유자 권한이나
개별 데이터 연결이 60초 동안 복구되지 않으면 해당 공유 I/O를 차단한다. root가 마운트 아래
inode를 FD로 고정하고 root 소유 mode000으로 보호한 뒤에만 VM 종료를 생략한다. 마운트와
보호를 증명하지 못하는 시작 오류는 기존 VM 종료 정책을 유지한다. 다음 부팅의 root broker만
그 디렉토리를 mode700으로 다시 열어 마운트하고 재봉인한다. 삭제용 host lease는 VM 중지와
host 작업자 종료를 별도로 증명할 때까지 유지한다.

세션에서는 host protocol client/emulator와 guest sessiond의 Unix 연결을 유지한다.
guest relay는 host CID 2만 받아들이고 sessiond는 Unix peer UID 0을 검증한다.
create/resume 비트와 소유자 토큰을 사용해 사라진 중계 세대를 resume으로 다시 만들지 않는다.
따라서 최초 ACK를 받기 전에 proxy가 죽어도 이전 입력을 새 RPC로 실행하지 않는다.

검토한 대안은 SSHFS 재마운트, application RPC 재시도, sequenced output pull/ACK와 별도
guest 출력 spool이다. 재마운트와 RPC 재시도는 열린 핸들 또는 mutation 결과의 불확실성을
해결하지 못한다. 출력 spool은 프로세스 사망 이후 복구에 유용하지만 새 wire, checkpoint와
cursor의 원자적 영속화가 필요하다. 이번 범위에서는 기존 session-v1과 mutation 순서를
유지하는 스트림 복구를 선택했다. 디스크를 덮어쓰거나 무제한 메모리를 사용하지 않는다.

## 로컬 검증

- 필수 Gradle 작업에 `:protocol:test`, `:aosp-avf-runtime:testDebugUnitTest`,
  `:android-app:assembleDebugAndroidTest`를 추가한 검증 통과. runtime 410개 중
  1개 skip, AVF 41개, image 68개, protocol 31개에서 실패 0개.
- ARM64 호스트의 기존 AAPT2 래퍼가 삭제된 Maven 캐시를 요구해 첫 APK 리소스 빌드가
  실패했다. 이미 존재하는 같은 버전 `9.3.0-15703166` 바이너리를 사용하는 임시 래퍼로
  수정해 재검증했다. 추적된 SDK/Gradle 설정이나 도구 버전은 바꾸지 않았다.
- Python 이미지 검사 140개 통과. 소켓 교체/중복 제거, 양방향 credit 포화, 유실된 세대 거부,
  control 복구, 개별 데이터 만료, 다음 부팅 mode700 재준비, port 준비 대기를 포함한다.
- Rust droid-sessiond 검사 20개 통과. 변경한 Python 소스의 py_compile 및 diff 검사 통과.
- Android MCP로 ReDroid에 APK와 test APK를 설치하고 실제 JNI/SFTP 파일을 연 뒤 rename,
  소켓 단절/교체, 같은 핸들 읽기/쓰기를 검증했다. `OK (1 test)`.
- Jev 설계 비교는 stream 복구를 선택했다(0.94). 최종 소스 gate는 composite 0.57,
  safe_to_apply 0.09로 자동 승인하지 않았고 구체적인 결함 설명 없이 낮은 확신과 test gap을
  반환했다. 이를 통과 근거로 사용하지 않는다. 실제 테스트 및 세대/ACK/취소/마운트 보호의
  소스 검토를 수행했고 이미지의 정상 부팅 검사를 별도 공개 조건으로 유지한다.
- GitHub 연결 도구는 저장소 권한 확인에 사용했다. workflow dispatch는 해당 연결 도구에
  없어 기존 Git credential helper를 이용한 gh로 실행했다. credential은 출력하거나 기록하지 않았다.

## 이미지 제작과 공개 검증

첫 r1 Ubuntu [40044504610](https://github.com/nolbo/TERM/actions/runs/40044504610)와
Debian [40044508672](https://github.com/nolbo/TERM/actions/runs/40044508672)는 정상 부팅 중
공유 파일/열린 핸들 재연결 뒤 새 session relay 포트에 단 한 번 접속해 초기화 시점과 경합했다.
이미지 공개 및 APK 단계는 실행되지 않았다. 같은 검사 문제를 가진 작업 소유 Arch 실행은 중단했다.
`3c5581dc798fd833aca405f70a996a9a64e1f726`에서 첫 연결만 60초 동안 준비를 기다리도록
수정했다. 아직 RPC를 보내기 전인 단계만 재시도하며, 이미 보낸 RPC는 재실행하지 않는다.

r2의 세 실행(40045803465 Ubuntu, 40045806806 Debian, 40045810084 Arch)은 이미지,
카탈로그 APK 및 공개 다운로드 검증까지 성공했다. 하지만 기본 카탈로그 승격 전 비교에서
현재 ext4-r2 커널 `fa4f0fc22c316d24a417e5620e915c626ab246ef5c190b557d38c136955357ee`와
r2 커널 `9b2331e74535a1171c395148fb57837dbe2b498062e0067560dd979fdeba14fb`가 달랐다.
동일 소스의 세 병렬 커널 제작 실행은 서로 다른 바이너리를 생성했으며 처음에 선택한 실행
39715161589는 현재 기본 이미지가 사용하는 실행이 아니었다. 동일 레시피 확인만으로는
구성요소 업데이트 호환성을 보장하지 못한다. r2 핀을 기본 카탈로그에 반영하지 않았다.

현재 세 배포판의 공개 manifest를 비교하고 성공 실행 39715151260의 커널 번들을 내려받아
실제 vmlinuz SHA-256이 현재 승인 커널과 정확히 같음을 확인했다. 이 번들로 r3를 제작한다.
이미지 wire/게스트 구현은 r2와 같으며 기존 설치 디스크는 변경하지 않는다.

호스트의 최초 공유 연결은 50초 준비 기한 안에서 짧은 간격으로 재시도하도록 조정했다.
초기화 중 15초 reconnect backoff가 늦게 준비된 broker를 건너뛰는 경우를 방지한다.
이후 복구 backoff와 guest wire는 바꾸지 않았고 AVF 모듈 검사 41개가 다시 통과했다.

r3 제작·APK·공개 검증이 모두 성공했고 기본 카탈로그로 승격했다. 제작 소스는
`e730980a2ef77b50edf0101c2647932bbd2d1807`이다. 세 배포판 모두 현재 승인 커널과
같은 `fa4f0fc22c316d24a417e5620e915c626ab246ef5c190b557d38c136955357ee`를 포함한다.

| 배포판 | 이미지 | CI | manifest SHA-256 |
| --- | --- | --- | --- |
| ubuntu | avf-ubuntu-recovery-20261024-r3 | [40048874959](https://github.com/nolbo/TERM/actions/runs/40048874959) | `f006fa1bc673babddd0050704bbd10ac03e0d8791f03a19a1fb1bf46ee010ec1` |
| debian | avf-debian-recovery-20261024-r3 | [40048879959](https://github.com/nolbo/TERM/actions/runs/40048879959) | `dbf57d644cb9409d85db8e1c7a58c6afe13781dd6b669805c47c3d0b8a46653b` |
| archlinux | avf-archlinux-recovery-20261024-r3 | [40048884964](https://github.com/nolbo/TERM/actions/runs/40048884964) | `b1f51f7416b9fdecbc1fe619ee75883ca0b66f1a00e5fe8e6a157b6a5e861efa` |

각 이미지의 두 차례 정상 부팅(총 6회)에서 session list, package port, session resume,
공유 read/write, 열린 핸들 재연결, owner loss 후 VM 유지와 mount guard 검사가 모두 passed다.
공식 publish 작업이 인증 없는 전체 공개 다운로드·압축 해제·raw SHA-256 검증을 수행한 뒤
생성한 승인 카탈로그 산출물을 사용했다. 로컬에서는 각 CI의 source/job/승인 산출물과 공개
manifest·metadata checksum·정상 부팅 증거·커널 해시의 일치를 검증하고, 동일한 세 핀만
기본 카탈로그로 병합했다. 같은 이미지의 전체 다운로드 검사를 다시 반복하지 않았다.
현재 ext4-r2의 공개 manifest 핀과 실제 재사용 커널 바이너리 해시도 비교했다.

새 이미지와 카탈로그는 기존 설치나 사용자 디스크를 자동으로 변경하지 않는다.
공개 이미지들은 draft=false, prerelease=true이며 알림은 notify=false로 생략했다.


## 적용 범위와 한계

기존 설치를 자동으로 변경하지 않는다. 호환 APK와 승인 이미지가 모두 필요하며, 중지한 환경의
명시적인 구성요소 업데이트는 검증된 복사본/health/activation 절차를 따른다. 커널이 다르면
기존 전체 전환 규칙과 데이터 이전 제한을 적용한다. v1 이미지의 fail-closed 동작은 유지한다.

복구 상태는 프로세스 메모리에만 있다. host/proxy/sessiond 사망 이후 화면·출력 복원이나 공유
핸들 복원을 보장하지 않는다. 공유 host owner를 잃은 실행환경은 기존 stop/start 요구가 유지된다.
전송 후 응답을 받지 못한 RPC 결과는 불명확하며 자동으로 재실행하지 않는다.
ReDroid의 JNI/SFTP 검사는 실제 AVF 기기 검사가 아니다. 특권 ARM64 AVF 기기에서의
백그라운드/전원 정책, 관리 부팅, 두 VM, 실제 vsock reset 및 stop/lease 정리는 미검증이다.

## 최종 카탈로그 APK

카탈로그 커밋 `1d2588b29ad1fc06f3302b463078f2f28c6ff9a5`에서 `:android-app:assembleDebug`가 통과했다.
버전은 `1.4.0+dev.214969769.1d2588b29ad1-debug`, 패키지는 `dev.terminal.app.debug`이며 크기는 68,487,914 bytes다.
APK SHA-256은 `7be3bcaaaa514cd7f398859b0b03c098a4d8a9a8a862f09a97eb1e4156ea630b`다.

`verify_apk`로 APK 내부 카탈로그와 커밋된 세 r3 승인 핀의 바이트 일치를 검증했고,
`tools/avf/verify-driver-apk.py`가 실제 AVF 드라이버 포함 및 platform API stub 미포함을
확인했다. apksigner의 v2 서명 검증도 통과했다. Debug 인증서 SHA-256은 `2ea8b58dfd147494e0810949c75db554519efa55b762cb7014119f9abdafb7e0`다.
산출물은 `android-app/build/outputs/apk/debug/android-app-debug.apk`다.

이 작업에서 공개한 것은 이미지 릴리스이며 APK는 호환 Debug 산출물이다.
설치된 앱/환경은 자동 변경하지 않았고 앱 채널 배포나 main 병합은 수행하지 않았다.
위 APK 생성 뒤의 변경은 검증 기록뿐이므로 APK를 다시 빌드하지 않는다.
