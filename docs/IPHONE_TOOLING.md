# iPhone tooling environment / iPhone 도구 실행 환경

## Product direction

The pinned idevice 0.1.65 crate receives a minimal parser bounds patch (see
tools/iphone/connector/README.md). Private connector acquisition is nonblocking,
deadline-bounded and poisoned after interruption, with no replay. Cached
RPPairing requires live pair-verify over the selected iPhone's private CoreDevice/
RSD tunnel before reuse. Verification/parse failure or a 60-second remote pairing
deadline yields `RP_PAIRING`, preserves the saved record and stops before pairing
placement; it never silently falls back to re-pairing. An iPhone may already have
Trust or Wi-Fi debugging state at this stage. No new guest record management
endpoint is provided; stale identity recovery requires a deliberate host-side
recovery decision, not repeated automatic pairing.

Wi-Fi debugging is read before writing. An already enabled value is unchanged;
an unreadable/non-boolean value fails closed. A successfully enabled value remains
enabled if later steps fail, and this is reported rather than silently resetting it.

Pairing placement writes a host-randomized staging file in the same Documents
directory, closes it, reads back and verifies it, then issues one AFC rename to
the final filename and verifies again. The live file is never opened for writing
or deleted to make rename succeed. If replacement is unsupported, fail without a
destructive fallback. An ambiguous rename result is not replayed or rolled back.
A staging file may remain after failure/cancellation; no cleanup is sent over an
uncertain AFC stream. AFC has no exclusive-create primitive, so staging uses a
random UUID plus fail-closed existence checking; this does not protect against a
concurrently hostile app modifying its own Documents directory. Actual device
rename/replacement behavior, including power loss, still requires hardware
validation. This does not claim filesystem crash-atomicity on every iOS version.

The existing iPhone pair/info/unpair and AFC operations retain their bounded
pair-record, Trust and transport behavior. Their failures expose only stable
stage/error categories and never pairing material. No iOS 27 beta compatibility
claim is made.

`tooling list` reports `serviceRevision`, `requestAction`, `requestId`, and
SHA-256 hashes of the two installed APK workers in `workerSha256`. Successful
tooling replies echo the action with a fresh host-generated response ID. These
IDs/hashes are diagnostic metadata, not authentication tokens. A CLI archive
hash alone does not identify the host-service/worker build. `check` returns
`tool: idevice-check`, `completed: true`, connector flags and `ideviceVersion`;
it never initiates Trust or installation.

The terminal supplies the conditions for the bounded iPhone probe and transport,
not a product-owned IPA installation screen. Native and Rootless use
the same session-scoped Android USB capability protocol. Android `:vmhost`
remains the physical host; calling a capability is explicit, not a runtime fallback.

Both environments support:

```sh
terminal-usb list
terminal-usb tooling list
terminal-usb tooling check DEVICE_ID
terminal-usb iphone pair DEVICE_ID
terminal-usb iphone info DEVICE_ID
terminal-usb iphone ls DEVICE_ID /Documents
terminal-usb iphone get DEVICE_ID AFC_PATH LOCAL_FILE
terminal-usb iphone put DEVICE_ID LOCAL_FILE --name import.bin
```

Each physical action requires its own approval in the app USB screen, plus
Android USB permission. Use the opaque attachment ID from `list`. Revocation,
USB detach, grant/session revocation and host death fail closed. Never
automatically replay device writes or resume privileged work after service
process death.

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
No transport API fork is needed (the parser bounds patch is separate). The probe opens two independent logical clients and
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
2. The dedicated signing/install recipe and its credential-bearing workflow are
   removed. Only the fixed `idevice-check` probe remains registered.
3. The host controller owns the device lease, threads, timers, processes and
   descriptors for the probe and the existing iPhone transport operations.
4. Remaining physical validation: real ARM64 USB Host Android and an iPhone,
   including detach during Trust, pairing and AFC placement. Emulator checks
   cannot establish physical-device success.

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
authentication or successful physical iPhone transport.

Native CLI access was also checked through the running Android terminal. The
available Android target lacks USB Host, and its Rootless base environment is
not prepared; a live Rootless CLI session and physical iPhone transport remain
unvalidated. Python client tests establish protocol behavior, not a live PRoot
installation. Do not translate either absence into a claimed successful flow.

## 한국어 안내

Native와 Rootless에서 같은 `terminal-usb` 명령으로 iPhone 관련 호스트
capability를 사용합니다. Android는 항상 직접 USB Host이며, 터미널에 USB FD나
usbmux 접속점·페어링 데이터·키를 전달하지 않습니다. 등록된 APK 도구는
`idevice-check` 검사뿐이며, 앱 설치·서명·Apple 계정 입력 capability는
제공하지 않습니다. 실제 iPhone 실기 검증은 별도입니다.
