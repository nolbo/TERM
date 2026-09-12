# iPhone tooling environment / iPhone 도구 실행 환경

## Product direction

The terminal supplies the conditions for tools to implement iPhone workflows,
not a product-owned SideStore/IPA installation screen. Native and Rootless use
the same session-scoped Android USB capability protocol. Android `:vmhost`
remains the physical host; calling a capability is explicit, not a runtime fallback.

Both environments support:

```sh
terminal-usb list
terminal-usb tooling list
terminal-usb tooling check DEVICE_ID
terminal-usb tooling start isideload DEVICE_ID
terminal-usb tooling status OPERATION_ID
terminal-usb tooling watch OPERATION_ID
terminal-usb tooling select OPERATION_ID CHOICE_ID[,CHOICE_ID]
terminal-usb tooling cancel OPERATION_ID
terminal-usb iphone pair DEVICE_ID
terminal-usb iphone info DEVICE_ID
terminal-usb iphone ls DEVICE_ID /Documents
terminal-usb iphone get DEVICE_ID AFC_PATH LOCAL_FILE
terminal-usb iphone put DEVICE_ID LOCAL_FILE --name import.bin
```

Each physical action requires its own approval in the app USB screen, plus
Android USB permission. Use the opaque attachment ID from `list`. Revocation,
USB detach, grant/session revocation and host death fail closed. Leaving an
Activity does not stop a live CLI/PTY or async tool. Disconnecting a watch client
does not cancel an async operation; use `cancel` explicitly. Never automatically
replay device writes or resume privileged work after service process death.

Native uses the ARM64 Android/Bionic APK-packaged `terminal-usb` binary, exposed
as an interactive shell alias. `$TERMINAL_USB_CLIENT` names that immutable
APK executable for non-interactive programs. Rootless uses the existing Python
stdlib client; its `Broker.call()` API can be used in scripts. These are equal
capabilities, not identical ELF/libc environments. Existing Rootless serial,
PyUSB, storage and raw-transfer APIs remain unchanged; this task does not port
all of those command frontends into Native.

## Private transport for registered host tools

`tooling check` invokes an APK-packaged `iphone-tool-probe`. The broker grants
one whole-device lease and starts the GPL USB owner in `mux-owner` mode. Only
that process receives the Android USB FD. A second APK-packaged process receives
one anonymous connector FD after same-UID and random-token authentication.

The connector accepts only byte `M`. Each request creates a fresh stream
socketpair; one end joins the existing mux client loop and the other is delivered
by SCM_RIGHTS to the trusted tool. There is no usbmux filesystem/TCP listener,
no `/dev/bus/usb` discovery and no public connection factory. The owner caps
connector requests and descriptors, exits on host/connector closure, and is
killed/reaped before its device lease is released.

The reusable Rust `terminal-iphone-connector` crate implements
`idevice::IdeviceProvider` using `UsbmuxdConnection::new` and fresh streams.
No idevice fork is needed. The probe opens two independent logical clients and
queries enumeration and BUID; it returns only capability-check flags, never a
UDID, pair record or BUID.

Neither terminal receives a connector FD. Raw pair records, RPPairing keys,
signing keys, passwords and 2FA codes are not a guest capability. Unmodified
tools expecting public usbmuxd are not made compatible by publishing one.
A privileged workflow must be implemented by an explicitly registered,
built-in APK tool behind bounded typed operations.

## Implementation plan and current boundary

1. Shared CLI capability access, private multi-client transport, trusted worker
   lifecycle, pinned Android builds and deterministic checks are implemented.
2. `tools/sidestore` is a pinned APK recipe packaged as `isideload`. Both clients
   can request the same fixed SideStore stable workflow. There is no app install
   action, arbitrary IPA picker or unrestricted tool argv/URL/API relay.
3. Generic **USB → Host tool inputs** handles login and 2FA through typed,
   verified-main-process Binder plus a one-use prompt nonce. Input drafts are not
   saved, autofill is disabled and FLAG_SECURE blocks screenshots. Both clients
   must request host input, never read Apple credentials in the Rootless PTY.
   Team/certificate selections may use the screen or scoped CLI choice IDs;
   revocation has no default selection. The anisette endpoint is host controlled,
   defaults to https://ani.sidestore.io and requires HTTPS without credentials,
   query, fragment or a non-443 port. No debug HTTP configuration is provided.
4. The host controller owns the device lease, threads, timers, processes and
   descriptors. It bounds records, prompts, IPC frames and operation duration;
   cancellation/detach/revocation clear queued input, kill/reap both workers,
   close descriptors and remove operation cache artifacts before releasing the
   lease. Orphan UUID cache directories are removed at service creation.
   Cancellation does not roll back an app already installed or a certificate
   already explicitly revoked; it stops further owned work and cleans host data.
5. Remaining physical validation: real ARM64 USB Host Android and an
   iPhone, including detach during signing/install/AFC placement. Emulator
   checks cannot establish successful physical installation or first refresh.

Extending the tool set requires a reviewed APK recipe and fixed typed input /
output schema, packaged-executable allowlisting, source/license bundles and
tests. Linux package installation alone does not register host authority. A
future arbitrary-IPA recipe would also need separate artifact/storage validation
and an explicit grant; it is not provided by this implementation.

This does not ship an arbitrary IPA installer, a Rootless usbmuxd daemon, or an
Android-hosted general Rust compiler. Rust/NDK cross-builds are APK development
steps; no external computer is part of on-device transport/runtime. Downloading
and executing a Native tool from writable app data is not an extension mechanism.
Rootless retains distribution package managers, but installing a Linux library
does not grant new Android authority.

## Validation boundary

The verification ladder includes Rust connector/reference-worker unit tests,
the Python USB client suite, Android runtime unit tests and the canonical APK
build. `IphoneToolingDeviceTest` and `IphoneWorkerDeviceTest` execute packaged
Android binaries with synthetic non-USB peers/descriptors. They check framing,
private fresh streams, descriptor handling and operation rejection, not Apple
authentication or successful physical installation.

Native CLI access was also checked through the running Android terminal. The
available Android target lacks USB Host, and its Rootless base environment is
not prepared; a live Rootless CLI session and physical iPhone transport remain
unvalidated. Python client tests establish protocol behavior, not a live PRoot
installation. Do not translate either absence into a claimed successful flow.

## 한국어 안내

Native와 Rootless에서 같은 `terminal-usb` 명령으로 iPhone 관련 호스트
capability를 사용합니다. 앱은 SideStore 설치 화면 대신 USB 권한·실행 기반을
제공합니다. Android는 항상 직접 USB Host이며, 터미널에 USB FD나 usbmux
접속점·페어링 데이터·키를 전달하지 않습니다.

현재 검사 도구와 고정 SideStore stable 작업을 수행하는 `isideload` APK 도구가
등록되어 있습니다. `start`가 반환한 작업 ID로 상태·진행을 관찰하고 취소할 수
있습니다. 계정·암호·2FA는 USB 화면의 **호스트 도구 입력**에서만 입력하세요.
팀·폐기 인증서는 UI 또는 CLI의 선택 ID로 명시적으로 결정합니다. 일반
usbmuxd나 임의 IPA 설치 capability는 아닙니다. 실제 설치·첫 갱신이 성공했다고
주장하지 않으며, 실기 검증이 남아 있습니다.

SideStore 예제를 확장할 때는 무료 Personal Team의 보통 7일 만료와 기기·
App ID·인증서 한도를 고려하세요. 개발자 모드, 개발자 프로파일 신뢰,
LocalDevVPN 활성화와 첫 갱신은 iPhone 사용자 조작이 필요합니다. Android가
보호된 iOS 설정을 우회하거나 자동으로 켤 수는 없습니다. Apple 및 서버의
동작은 앱과 독립적으로 바뀔 수 있습니다.
